package com.example.application.acquisition

import com.example.domain.inference.InferenceService
import com.example.infrastructure.content.ContentSource
import com.example.infrastructure.content.SourceQuery
import com.example.infrastructure.lucene.LuceneAdapter
import com.example.infrastructure.sqlite.AcquisitionJobRepository
import com.example.infrastructure.sqlite.ItemRepository
import com.example.infrastructure.storage.ObjectStorePort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.time.Instant

class AcquisitionService(
    private val jobRepository: AcquisitionJobRepository,
    private val inferenceService: InferenceService,
    private val itemRepository: ItemRepository,
    private val luceneAdapter: LuceneAdapter,
    private val objectStore: ObjectStorePort,
    private val activeEmbeddingVersion: () -> String,
) {
    fun createJob(
        jobId: String,
        source: String,
        tags: List<String>,
    ): String {
        jobRepository.insert(
            id = jobId,
            status = "pending",
            origin = source,
            query = tags.joinToString(","),
        )
        return jobId
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun run(
        jobId: String,
        source: String,
        tags: List<String>,
        limit: Int,
        contentSource: ContentSource,
    ) {
        jobRepository.updateStatus(jobId, "running")
        if (limit == 0) {
            jobRepository.updateStatus(jobId, "done", Instant.now().epochSecond)
            return
        }
        try {
            contentSource
                .fetch(SourceQuery(tags, limit))
                .flatMapMerge(PIPELINE_CONCURRENCY) { image ->
                    flow {
                        val vec =
                            withContext(Dispatchers.Default) {
                                inferenceService.embed(image.bytes)
                            }
                        val itemId =
                            withContext(Dispatchers.IO) {
                                val id =
                                    itemRepository.insertIdempotent(
                                        md5 = image.md5,
                                        url = image.cdnUrl,
                                        origin = image.originPostUrl,
                                        rating = image.rating,
                                        embeddingVersion = activeEmbeddingVersion(),
                                        indexedAt = Instant.now().epochSecond,
                                    )
                                luceneAdapter.write(id, vec)
                                id
                            }
                        objectStore.put("images/${image.md5}", image.bytes)
                        emit(itemId)
                    }
                }.collect()
            luceneAdapter.refresh()
            jobRepository.updateStatus(jobId, "done", Instant.now().epochSecond)
        } catch (e: Exception) {
            jobRepository.updateStatus(jobId, "failed", Instant.now().epochSecond)
            throw e
        }
    }

    fun getJob(jobId: String): AcquisitionJobRow? = jobRepository.findById(jobId)

    companion object {
        const val PIPELINE_CONCURRENCY = 10
    }
}
