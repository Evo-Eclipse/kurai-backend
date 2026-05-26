package com.example.domain.cluster

import com.example.domain.model.UserProfile
import com.example.domain.profile.Scoring
import kotlin.random.Random

class ClusterService private constructor(
    private val centroids: Array<FloatArray>,
) {
    fun assignCluster(vector: FloatArray): Int = centroids.indices.maxByOrNull { Scoring.cos(centroids[it], vector) }!!

    fun epsilonCandidates(
        profile: UserProfile,
        k: Int,
        seed: Long,
    ): List<Int> {
        val rng = Random(seed)
        val occupied =
            profile.positivePrototypes
                .map { assignCluster(it.vector) }
                .toSet()

        if (occupied.isEmpty()) {
            return (0 until EXPECTED_K).toMutableList().shuffled(rng).take(k)
        }

        // Prefer clusters not occupied by any positive prototype.
        // Pre-assign random keys so the comparator is stable.
        val keyed = (0 until EXPECTED_K).map { idx -> idx to rng.nextInt() }
        val candidates =
            keyed
                .sortedWith(compareBy({ (idx, _) -> if (idx in occupied) 1 else 0 }, { (_, key) -> key }))
                .map { (idx, _) -> idx }
        return candidates.take(k)
    }

    companion object {
        fun load(): ClusterService {
            val stream =
                ClusterService::class.java.getResourceAsStream("/clusters_vitb16.bin")
                    ?: error("clusters_vitb16.bin not found on classpath")
            return ClusterService(loadCentroids(stream))
        }

        internal fun fromCentroids(centroids: Array<FloatArray>): ClusterService = ClusterService(centroids)
    }
}
