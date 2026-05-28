package com.example.workers

import com.example.domain.cluster.ClusterService
import com.example.domain.profile.Scoring
import com.example.infrastructure.lucene.LuceneAdapter
import com.example.infrastructure.sqlite.ItemRepository
import com.example.infrastructure.storage.LocalObjectStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

private val log = LoggerFactory.getLogger(KMeansScheduler::class.java)

class KMeansScheduler(
    private val itemRepo: ItemRepository,
    private val luceneAdapter: LuceneAdapter,
    private val objectStore: LocalObjectStore,
    private val clustersKey: String,
    private val clusterServiceRef: AtomicReference<ClusterService?>,
    private val intervalMs: Long = 3_600_000,
    private val minGrowthFactor: Double = 1.10,
    private val minAgeMs: Long = 24 * 3_600_000L,
    private val sampleLimit: Int = 50_000,
) {
    private var lastKnownCount: Long = 0L
    private var lastRetrainedAt: Long = 0L

    suspend fun run() {
        while (true) {
            delay(intervalMs)
            check()
        }
    }

    private suspend fun check() {
        val currentCount = withContext(Dispatchers.IO) { itemRepo.countAll() }
        val now = System.currentTimeMillis()
        val growthMet = lastKnownCount > 0 && currentCount >= (lastKnownCount * minGrowthFactor).toLong()
        val ageMet = (now - lastRetrainedAt) >= minAgeMs
        if (!growthMet || !ageMet) return

        log.info("KMeansScheduler: triggering retraining (count=$currentCount, prev=$lastKnownCount)")
        val sampleIds = withContext(Dispatchers.IO) { itemRepo.loadSample(sampleLimit) }
        val vectors = sampleIds.mapNotNull { luceneAdapter.getVector(it) }
        if (vectors.size < 2) {
            log.warn("KMeansScheduler: insufficient vectors (${vectors.size}) to retrain — skipping")
            return
        }

        val k = clusterServiceRef.get()?.size?.takeIf { it >= 2 } ?: DEFAULT_K
        val centroids = trainKMeans(vectors, k = minOf(k, vectors.size), seed = now)
        val bytes = serializeCentroids(centroids)

        objectStore.put(clustersKey, bytes)
        clusterServiceRef.set(ClusterService.fromCentroids(centroids))

        lastKnownCount = currentCount
        lastRetrainedAt = now
        log.info("KMeansScheduler: retraining complete (k=${centroids.size})")
    }

    companion object {
        const val DEFAULT_K: Int = 23

        internal fun trainKMeans(
            vectors: List<FloatArray>,
            k: Int,
            seed: Long,
        ): Array<FloatArray> {
            require(k >= 2) { "k must be ≥ 2, got $k" }
            val rng = Random(seed)
            val centroids = kMeansPlusPlusInit(vectors, k, rng).toMutableList()
            val batchSize = minOf(100, vectors.size)
            repeat(50) {
                val batch = vectors.shuffled(rng).take(batchSize)
                val counts = IntArray(k)
                val sums = Array(k) { FloatArray(centroids[0].size) }
                for (v in batch) {
                    val c = nearestCentroid(centroids, v)
                    counts[c]++
                    for (i in sums[c].indices) sums[c][i] += v[i]
                }
                for (c in 0 until k) {
                    if (counts[c] > 0) {
                        for (i in sums[c].indices) sums[c][i] /= counts[c]
                        centroids[c] = Scoring.l2Normalize(sums[c])
                    }
                }
            }
            return centroids.toTypedArray()
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

        private fun kMeansPlusPlusInit(
            vectors: List<FloatArray>,
            k: Int,
            rng: Random,
        ): List<FloatArray> {
            val centroids = mutableListOf(vectors[rng.nextInt(vectors.size)])
            while (centroids.size < k) {
                val dists =
                    vectors.map { v ->
                        val nearest = centroids.minOf { c -> 1.0 - Scoring.cos(c, v) }
                        maxOf(0.0, nearest)
                    }
                val total = dists.sum()
                var pick = rng.nextDouble() * total
                var chosen = vectors.last()
                for (i in vectors.indices) {
                    pick -= dists[i]
                    if (pick <= 0.0) {
                        chosen = vectors[i]
                        break
                    }
                }
                centroids.add(chosen.copyOf())
            }
            return centroids
        }

        private fun nearestCentroid(
            centroids: List<FloatArray>,
            v: FloatArray,
        ): Int = centroids.indices.maxByOrNull { Scoring.cos(centroids[it], v) }!!
    }
}
