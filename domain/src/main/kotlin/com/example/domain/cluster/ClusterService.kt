package com.example.domain.cluster

import com.example.domain.model.UserProfile
import com.example.domain.profile.Scoring
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random

class ClusterService private constructor(
    private val centroids: Array<FloatArray>,
) {
    val size: Int get() = centroids.size
    val dim: Int get() = centroids.firstOrNull()?.size ?: error("centroids array is empty")

    fun assignCluster(vector: FloatArray): Int {
        require(vector.size == dim) {
            "Vector dimension ${vector.size} does not match centroid dimension $dim"
        }
        return centroids.indices.maxByOrNull { Scoring.cos(centroids[it], vector) }
            ?: error("centroids array is empty")
    }

    fun epsilonCandidates(
        profile: UserProfile,
        k: Int,
        seed: Long,
    ): List<Int> {
        require(k in 1..centroids.size) { "k must be in [1, ${centroids.size}], got $k" }
        val rng = Random(seed)
        val occupied =
            profile.positivePrototypes
                .map { assignCluster(it.vector) }
                .toSet()

        if (occupied.isEmpty()) {
            return (0 until centroids.size).toMutableList().shuffled(rng).take(k)
        }

        // Prefer clusters not occupied by any positive prototype.
        // Pre-assign random keys so the comparator is stable.
        val keyed = (0 until centroids.size).map { idx -> idx to rng.nextInt() }
        val candidates =
            keyed
                .sortedWith(compareBy({ (idx, _) -> if (idx in occupied) 1 else 0 }, { (_, key) -> key }))
                .map { (idx, _) -> idx }
        return candidates.take(k)
    }

    companion object {
        fun load(path: Path): ClusterService = ClusterService(loadCentroids(Files.newInputStream(path)))

        fun fromCentroids(centroids: Array<FloatArray>): ClusterService = ClusterService(centroids)
    }
}
