package com.example.application.catalog

import com.example.domain.catalog.CatalogItemPort
import com.example.domain.catalog.ClusterGenerationPort
import com.example.domain.catalog.ItemVectorIndexPort
import com.example.domain.catalog.SystemStatePort
import com.example.domain.cluster.ClusterService
import com.example.domain.profile.kMeansCentroids
import com.example.domain.storage.ObjectStorePort
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
    private val itemRepo: CatalogItemPort,
    private val vectorIndex: ItemVectorIndexPort,
    private val objectStore: ObjectStorePort,
    private val clusterGenerations: ClusterGenerationPort,
    private val systemState: SystemStatePort,
    private val clusterServiceRef: AtomicReference<ClusterService?>,
    private val intervalMs: () -> Long,
    private val minGrowthFactor: Double = 1.10,
    private val minAgeMs: Long = 24 * 3_600_000L,
    private val sampleLimit: Int = 50_000,
) {
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
        val now = System.currentTimeMillis()
        val totalItems = withContext(Dispatchers.IO) { itemRepo.countAll() }
        val embeddedItems = vectorIndex.numDocs().toLong()
        systemState.setCounts(totalItems = totalItems, embeddedItems = embeddedItems, now = now)

        val state = systemState.read()
        val active = state.activeClusterId?.let { clusterGenerations.findById(it) }
        val baseline = active?.catalogSizeAtBuild ?: 0L
        val lastActivatedAt = active?.activatedAt ?: 0L
        val growthMet = active == null || totalItems >= (baseline * minGrowthFactor).toLong()
        val ageMet = (now - lastActivatedAt) >= minAgeMs
        if (!growthMet || !ageMet) return

        val embeddingVersion = state.defaultEmbeddingVersion
        if (embeddingVersion == null) {
            log.warn("KMeansScheduler: no active embedding version — skipping cluster build")
            return
        }

        log.info("KMeansScheduler: triggering retraining (total=$totalItems, baseline=$baseline)")
        val sampleIds = withContext(Dispatchers.IO) { itemRepo.loadSample(sampleLimit) }
        val vectors = sampleIds.mapNotNull { vectorIndex.getVector(it) }
        if (vectors.size < 2) {
            log.warn("KMeansScheduler: insufficient vectors (${vectors.size}) to retrain — skipping")
            return
        }

        val k = clusterServiceRef.get()?.size?.takeIf { it >= 2 } ?: DEFAULT_K
        val centroids = trainKMeans(vectors, k = minOf(k, vectors.size), seed = now)

        val centroidsKey = "clusters/${embeddingVersion}_$now.bin"
        withContext(Dispatchers.IO) { objectStore.put(centroidsKey, serializeCentroids(centroids)) }
        val clusterId =
            clusterGenerations.createBuilding(
                embeddingVersion = embeddingVersion,
                clusterCount = centroids.size,
                catalogSizeAtBuild = totalItems,
                centroidsPath = centroidsKey,
            )
        systemState.activateCluster(clusterId, now)
        clusterServiceRef.set(ClusterService.fromCentroids(centroids))
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
            val buf = ByteBuffer.allocate(8 + k * dim * 4).order(ByteOrder.LITTLE_ENDIAN)
            buf.putInt(k)
            buf.putInt(dim)
            for (c in centroids) for (f in c) buf.putFloat(f)
            return buf.array()
        }
    }
}
