package com.example.application.profile

import com.example.application.embedding.CachingEmbeddingAdapter
import com.example.domain.model.Prototype
import com.example.domain.profile.Scoring
import com.example.domain.profile.silhouette
import com.example.domain.profile.splitPrototypes
import com.example.infrastructure.sqlite.EventRepository
import com.example.infrastructure.sqlite.PrototypeRepository
import com.example.infrastructure.sqlite.PrototypeRow
import com.example.infrastructure.sqlite.PrototypeType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(ProtoSplitWorker::class.java)

class ProtoSplitWorker(
    private val cachingProfile: CachingProfileAdapter,
    private val cachingEmbedding: CachingEmbeddingAdapter,
    private val prototypeRepo: PrototypeRepository,
    private val eventRepo: EventRepository,
    private val intervalMs: () -> Long,
) {
    suspend fun run() {
        try {
            while (true) {
                delay(intervalMs())
                sweep()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("ProtoSplitWorker crashed; worker stopped permanently", e)
        }
    }

    internal suspend fun sweep() {
        for (userId in cachingProfile.cachedUserIds()) {
            tryMigrateUser(userId)
        }
    }

    private suspend fun tryMigrateUser(userId: Long) {
        val profile = cachingProfile.getOrLoad(userId)
        if (profile.positivePrototypes.size >= MAX_POS) return

        val positiveEvents =
            withContext(Dispatchers.IO) {
                eventRepo.loadPositiveSince(userId, sinceEventId = 0L)
            }
        if (positiveEvents.size < MIN_EVENTS) return

        val vecs = cachingEmbedding.lookupVectors(positiveEvents.map { it.itemId })
        val positiveVecs = positiveEvents.mapNotNull { vecs[it.itemId] }
        if (positiveVecs.size < MIN_EVENTS) return

        val (bestK, assignments, score) = bestKWithSilhouette(positiveVecs, seed = userId)
        if (score <= MIN_SILHOUETTE) return

        val newPrototypes = buildPrototypes(positiveVecs, assignments, bestK).take(MAX_POS)
        val rows =
            newPrototypes.map { p ->
                PrototypeRow(
                    prototypeType = PrototypeType.POSITIVE,
                    vector = p.vector,
                    weight = p.weight.toDouble(),
                    embeddingVersion = profile.embeddingVersion.value,
                )
            }
        withContext(Dispatchers.IO) { prototypeRepo.replaceAll(userId, rows) }
        cachingProfile.forceUpdate(userId, profile.copy(positivePrototypes = newPrototypes))
        log.debug("ProtoSplitWorker: split userId=$userId into $bestK prototypes (silhouette=$score)")
    }

    private fun bestKWithSilhouette(
        vecs: List<FloatArray>,
        seed: Long,
    ): Triple<Int, List<Int>, Double> {
        val maxK = minOf(MAX_POS, vecs.size)
        if (maxK < 2) return Triple(1, vecs.indices.map { 0 }, 0.0)
        var bestK = 2
        var bestAssignments = emptyList<Int>()
        var bestScore = -Double.MAX_VALUE
        for (k in 2..maxK) {
            val assignments = splitPrototypes(vecs, k, seed = seed)
            val score = silhouette(vecs, assignments)
            if (score > bestScore) {
                bestScore = score
                bestK = k
                bestAssignments = assignments
            }
        }
        return Triple(bestK, bestAssignments, bestScore)
    }

    private fun buildPrototypes(
        vecs: List<FloatArray>,
        assignments: List<Int>,
        k: Int,
    ): List<Prototype> =
        (0 until k).mapNotNull { c ->
            val clusterVecs = vecs.indices.filter { assignments[it] == c }.map { vecs[it] }
            if (clusterVecs.isEmpty()) return@mapNotNull null
            val centroid =
                FloatArray(vecs[0].size) { i ->
                    clusterVecs.sumOf { it[i].toDouble() }.toFloat() / clusterVecs.size
                }
            Prototype(vector = Scoring.l2Normalize(centroid), weight = 1.0f)
        }

    companion object {
        const val MIN_EVENTS = 30
        const val MIN_SILHOUETTE = 0.25
        const val MAX_POS = 5
    }
}
