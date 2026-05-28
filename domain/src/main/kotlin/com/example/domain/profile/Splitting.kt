package com.example.domain.profile

import kotlin.random.Random

fun kMeansCentroids(
    vectors: List<FloatArray>,
    k: Int,
    seed: Long,
): Array<FloatArray> {
    require(vectors.isNotEmpty()) { "vectors must not be empty" }
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

fun splitPrototypes(
    vectors: List<FloatArray>,
    k: Int,
    seed: Long,
): List<Int> {
    require(k in 2..5) { "k must be in [2, 5], got $k" }
    if (vectors.isEmpty()) return emptyList()
    val centroids = kMeansCentroids(vectors, k, seed)
    return vectors.map { nearestCentroid(centroids.toList(), it) }
}

fun silhouette(
    vectors: List<FloatArray>,
    assignments: List<Int>,
): Double {
    require(vectors.size == assignments.size)
    if (vectors.size < 2) return 0.0
    val k = (assignments.maxOrNull() ?: 0) + 1
    if (k < 2) return 0.0
    var total = 0.0
    for (i in vectors.indices) {
        val ci = assignments[i]
        val sameCluster = assignments.indices.filter { assignments[it] == ci && it != i }
        val a =
            if (sameCluster.isEmpty()) {
                0.0
            } else {
                sameCluster.sumOf { j -> (1.0 - Scoring.cos(vectors[i], vectors[j])) } / sameCluster.size
            }
        var b = Double.MAX_VALUE
        for (c in 0 until k) {
            if (c == ci) continue
            val otherCluster = assignments.indices.filter { assignments[it] == c }
            if (otherCluster.isEmpty()) continue
            val dist = otherCluster.sumOf { j -> (1.0 - Scoring.cos(vectors[i], vectors[j])) } / otherCluster.size
            if (dist < b) b = dist
        }
        if (b == Double.MAX_VALUE) b = 0.0
        val denom = maxOf(a, b)
        total += if (denom == 0.0) 0.0 else (b - a) / denom
    }
    return total / vectors.size
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
        val totalDist = dists.sum()
        var pick = rng.nextDouble() * totalDist
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
): Int =
    centroids.indices.maxByOrNull { Scoring.cos(centroids[it], v) }
        ?: error("centroids must not be empty")
