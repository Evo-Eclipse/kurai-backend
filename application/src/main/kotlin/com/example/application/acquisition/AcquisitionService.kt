package com.example.application.acquisition

import com.example.domain.catalog.AcquisitionJobPort
import com.example.domain.catalog.CatalogItemPort
import com.example.domain.catalog.ItemVectorIndexPort
import com.example.domain.content.ContentSource
import com.example.domain.content.SourceQuery
import com.example.domain.inference.InferenceService
import com.example.domain.storage.ObjectStorePort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.time.Instant

data class JobStatus(
    val id: String,
    val status: String,
    val createdAt: Long,
    val completedAt: Long?,
    val errorMessage: String?,
)

class AcquisitionService(
    private val jobRepository: AcquisitionJobPort,
    private val inferenceService: InferenceService,
    private val itemRepository: CatalogItemPort,
    private val vectorIndex: ItemVectorIndexPort,
    private val objectStore: ObjectStorePort,
    private val activeEmbeddingVersion: () -> String,
    private val contentSources: Map<String, ContentSource> = emptyMap(),
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
            jobRepository.updateStatus(jobId, "done", Instant.now().toEpochMilli())
            return
        }
        try {
            contentSource
                .fetch(SourceQuery(tags, limit))
                .flatMapMerge(PIPELINE_CONCURRENCY) { image ->
                    flow {
                        val (itemId, isNew) =
                            withContext(Dispatchers.IO) {
                                itemRepository.insertIdempotent(
                                    md5 = image.md5,
                                    url = image.cdnUrl,
                                    origin = image.originPostUrl,
                                    rating = image.rating,
                                    embeddingVersion = activeEmbeddingVersion(),
                                    indexedAt = Instant.now().toEpochMilli(),
                                )
                            }
                        if (isNew) {
                            val vec = inferenceService.embed(image.bytes)
                            withContext(Dispatchers.IO) {
                                vectorIndex.write(itemId, vec)
                            }
                            objectStore.put("images/${image.md5}", image.bytes)
                        }
                        emit(itemId)
                    }
                }.collect()
            vectorIndex.refresh()
            jobRepository.updateStatus(jobId, "done", Instant.now().toEpochMilli())
        } catch (e: Exception) {
            runCatching { vectorIndex.refresh() }
            jobRepository.updateStatus(jobId, "failed", Instant.now().toEpochMilli(), e.message)
            throw e
        }
    }

    fun knownSources(): Set<String> = contentSources.keys

    suspend fun run(
        jobId: String,
        source: String,
        tags: List<String>,
        limit: Int,
    ) {
        val contentSource =
            requireNotNull(contentSources[source]) { "No ContentSource registered for: $source" }
        run(jobId, source, tags, limit, contentSource)
    }

    fun getJob(jobId: String): JobStatus? =
        jobRepository.findById(jobId)?.let { row ->
            JobStatus(
                id = row.id,
                status = row.status,
                createdAt = row.createdAt,
                completedAt = row.completedAt,
                errorMessage = row.errorMessage,
            )
        }

    companion object {
        const val PIPELINE_CONCURRENCY = 10
    }
}
