package com.example.application.acquisition

import com.example.domain.catalog.AcquisitionJobPort
import com.example.domain.catalog.CatalogItemPort
import com.example.domain.catalog.ItemVectorIndexPort
import com.example.domain.content.ContentSource
import com.example.domain.content.RawImage
import com.example.domain.content.SourceQuery
import com.example.domain.inference.InferenceService
import com.example.domain.storage.ObjectStorePort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
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
    private val activeEmbeddingVersion: suspend () -> String,
    private val contentSources: Map<String, ContentSource> = emptyMap(),
) {
    suspend fun createJob(
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
            val embeddingVersion = activeEmbeddingVersion()
            coroutineScope {
                val semaphore = Semaphore(PIPELINE_CONCURRENCY)
                contentSource.fetch(SourceQuery(tags, limit)) { image ->
                    // Acquire before launch so onImage suspends once
                    // PIPELINE_CONCURRENCY images are in flight: this backpressures
                    // the source and bounds the image bytes held in memory.
                    semaphore.acquire()
                    launch {
                        try {
                            persist(image, embeddingVersion)
                        } finally {
                            semaphore.release()
                        }
                    }
                }
            }
            vectorIndex.refresh()
            jobRepository.updateStatus(jobId, "done", Instant.now().toEpochMilli())
        } catch (e: Exception) {
            runCatching { vectorIndex.refresh() }
            jobRepository.updateStatus(jobId, "failed", Instant.now().toEpochMilli(), e.message)
            throw e
        }
    }

    /**
     * Idempotently persists one fetched [image]: upsert the catalog row, then
     * — only when the row is newly inserted — embed, write the vector, and store
     * the bytes. [precomputedVector] lets a caller that already embedded the
     * image (the proxy content path) skip a redundant inference pass.
     *
     * Does not refresh the vector index; batch callers refresh once at the end.
     */
    suspend fun persist(
        image: RawImage,
        embeddingVersion: String,
        precomputedVector: FloatArray? = null,
    ) {
        val (itemId, isNew) =
            withContext(Dispatchers.IO) {
                itemRepository.insertIdempotent(
                    md5 = image.md5,
                    url = image.cdnUrl,
                    origin = image.originPostUrl,
                    rating = image.rating,
                    embeddingVersion = embeddingVersion,
                    indexedAt = Instant.now().toEpochMilli(),
                )
            }
        if (isNew) {
            val vec = precomputedVector ?: inferenceService.embed(image.bytes)
            withContext(Dispatchers.IO) {
                vectorIndex.write(itemId, vec)
            }
            objectStore.put(imageObjectKey(image.md5, image.bytes), image.bytes)
        }
    }

    /**
     * Archives raw image bytes to the object store (Space) under the readable
     * [imageObjectKey], without touching the catalog or vector index. Backs the
     * shuttle content path, where the client already holds the images and only
     * wants metadata back; we keep a copy of the bytes but do not enrol them in
     * the recommendation corpus. Same content -> same key, so puts are idempotent.
     */
    suspend fun archiveToStore(images: List<ByteArray>) {
        images.forEach { bytes -> objectStore.put(imageObjectKey(md5Hex(bytes), bytes), bytes) }
    }

    /**
     * Write-behind for the proxy content path: persists a batch of already-fetched,
     * already-embedded images (reusing their vectors) under the same in-flight
     * cap as [run], then refreshes the vector index once.
     */
    suspend fun persistBatch(
        images: List<Pair<RawImage, FloatArray>>,
        embeddingVersion: String,
    ) {
        if (images.isEmpty()) return
        coroutineScope {
            val semaphore = Semaphore(PIPELINE_CONCURRENCY)
            images.forEach { (image, vector) ->
                semaphore.acquire()
                launch {
                    try {
                        persist(image, embeddingVersion, vector)
                    } finally {
                        semaphore.release()
                    }
                }
            }
        }
        vectorIndex.refresh()
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

    suspend fun getJob(jobId: String): JobStatus? =
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
        /**
         * Max in-flight images per acquisition job and the acquisition worker-scope
         * parallelism cap (see Application.installCore AcquisitionScope wiring).
         */
        const val PIPELINE_CONCURRENCY = 10
    }
}
