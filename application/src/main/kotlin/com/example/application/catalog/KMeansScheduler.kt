package com.example.application.catalog

import com.example.domain.cluster.ClusterService
import com.example.domain.profile.kMeansCentroids
import com.example.infrastructure.lucene.LuceneAdapter
import com.example.infrastructure.sqlite.ClusterGenerationRepository
import com.example.infrastructure.sqlite.ItemRepository
import com.example.infrastructure.sqlite.SystemStateRepository
import com.example.infrastructure.storage.LocalObjectStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference

private val log = LoggerFactory.getLogger(KMeansScheduler::class.java)

class KMeansScheduler(
    private val itemRepo: ItemRepository,
    private val luceneAdapter: LuceneAdapter,
    private val objectStore: LocalObjectStore,
    private val clusterGenerations: ClusterGenerationRepository,
    private val systemState: SystemStateRepository,
    private val clusterServiceRef: AtomicReference<ClusterService?>,
    private val intervalMs: () -> Long,
    private val minGrowthFactor: Double = 1.10,
    private val minAgeMs: Long = 24 * 3_600_000L,
    private val sampleLimit: Int = 50_000,
) {
    private var lastKnownCount: Long = 0L
    private var lastRetrainedAt: Long = 0L

    suspend fun run() {
        try {
            while (true) {
                delay(intervalMs())
                check()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("KMeansScheduler crashed; worker stopped permanently", e)
        }
    }

    internal suspend fun check() {
        val currentCount = withContext(Dispatchers.IO) { itemRepo.countAll() }
        val now = System.currentTimeMillis()
        val growthMet = lastKnownCount == 0L || currentCount >= (lastKnownCount * minGrowthFactor).toLong()
        val ageMet = (now - lastRetrainedAt) >= minAgeMs
        if (!growthMet || !ageMet) return

        val embeddingVersion = systemState.read().defaultEmbeddingVersion
        if (embeddingVersion == null) {
            log.warn("KMeansScheduler: no active embedding version — skipping cluster build")
            return
        }

        log.info("KMeansScheduler: triggering retraining (count=$currentCount, prev=$lastKnownCount)")
        val sampleIds = withContext(Dispatchers.IO) { itemRepo.loadSample(sampleLimit) }
        val vectors = sampleIds.mapNotNull { luceneAdapter.getVector(it) }
        if (vectors.size < 2) {
            log.warn("KMeansScheduler: insufficient vectors (${vectors.size}) to retrain — skipping")
            return
        }

        val k = clusterServiceRef.get()?.size?.takeIf { it >= 2 } ?: DEFAULT_K
        val centroids = trainKMeans(vectors, k = minOf(k, vectors.size), seed = now)

        // Persist the centroids under a per-generation key, register the build,
        // and flip system_state.active_cluster_id atomically; then swap the
        // in-memory reference so live readers pick up the new clusters.
        val centroidsKey = "clusters/${embeddingVersion}_$now.bin"
        withContext(Dispatchers.IO) { objectStore.put(centroidsKey, serializeCentroids(centroids)) }
        val clusterId =
            clusterGenerations.createBuilding(
                embeddingVersion = embeddingVersion,
                clusterCount = centroids.size,
                catalogSizeAtBuild = currentCount,
                centroidsPath = centroidsKey,
            )
        systemState.activateCluster(clusterId, now)
        clusterServiceRef.set(ClusterService.fromCentroids(centroids))

        lastKnownCount = currentCount
        lastRetrainedAt = now
        log.info("KMeansScheduler: activated cluster generation id=$clusterId (k=${centroids.size})")
    }

    companion object {
        const val DEFAULT_K: Int = 23

        internal fun trainKMeans(
            vectors: List<FloatArray>,
            k: Int,
            seed: Long,
        ): Array<FloatArray> {
            require(k >= 2) { "k must be ≥ 2, got $k" }
            return kMeansCentroids(vectors, k, seed)
        }

        internal fun serializeCentroids(centroids: Array<FloatArray>): ByteArray {
            val k = centroids.size
            val dim = centroids.firstOrNull()?.size ?: 0
            // Format: uint32 k (LE) | uint32 dim (LE) | k × dim × float32 (LE)
            val buf = ByteBuffer.allocate(8 + k * dim * 4).order(ByteOrder.LITTLE_ENDIAN)
            buf.putInt(k)
            buf.putInt(dim)
            for (c in centroids) for (f in c) buf.putFloat(f)
            return buf.array()
        }
    }
}
