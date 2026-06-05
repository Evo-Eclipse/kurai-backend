package com.example.application.profile

import com.example.application.embedding.CachingEmbeddingAdapter
import com.example.domain.cluster.ClusterService
import com.example.domain.model.EmbeddingVersion
import com.example.domain.model.UserProfile
import com.example.domain.profile.Scoring
import com.example.domain.profile.mmr

/** A scored candidate: the item id and its similarity score against the profile. */
data class ScoredItem(
    val itemId: Long,
    val score: Float,
)

/** Outcome of a ranking request; the handler maps each case to HTTP. */
sealed interface RankingOutcome {
    data class Ranked(
        val items: List<ScoredItem>,
    ) : RankingOutcome

    /** The user's profile is on a stale embedding version; ask the caller to retry. */
    data object VersionMismatch : RankingOutcome
}

/**
 * Candidate ranking orchestration: profile load, embedding-version gate,
 * scoring, and selection (warm MMR / cold-start cluster stratification /
 * arbitrary fallback). Transport-agnostic so the policy is unit-testable
 * without the HTTP layer; [com.example.profile.RankingHandler] keeps only
 * request validation and the [RankingOutcome] -> HTTP mapping.
 */
class RankingService(
    private val cachingProfile: CachingProfileAdapter,
    private val cachingEmbedding: CachingEmbeddingAdapter,
    private val getClusterService: () -> ClusterService?,
    private val activeEmbeddingVersion: suspend () -> EmbeddingVersion,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    suspend fun rank(
        userId: Long,
        candidateIds: List<Long>,
        topK: Int,
    ): RankingOutcome {
        val profile = cachingProfile.getOrLoad(userId)
        if (profile.embeddingVersion != activeEmbeddingVersion()) {
            return RankingOutcome.VersionMismatch
        }

        val vecs = cachingEmbedding.lookupVectors(candidateIds)
        val scored = candidateIds.mapNotNull { id -> vecs[id]?.let { id to Scoring.score(profile, it) } }
        val scoredMap: Map<Long, Float> = scored.toMap()

        val clusterService = getClusterService()
        val finalIds: List<Long> =
            when {
                profile.positivePrototypes.isNotEmpty() ->
                    scored.mmr(vecs, lambda = MMR_LAMBDA, n = topK).distinct().take(topK)
                clusterService != null -> {
                    // Cold-start: no positive prototypes yet -- distribute across target
                    // clusters. Seed combines userId with a daily bucket so the ordering
                    // is stable within a day but rotates daily and differs across users.
                    val seed = userId xor (clock() / MILLIS_PER_DAY)
                    coldStartRanking(
                        candidateIds.filter { it in vecs },
                        vecs,
                        minOf(topK, clusterService.size),
                        clusterService,
                        profile,
                        seed,
                    )
                }
                else -> candidateIds.take(topK)
            }

        return RankingOutcome.Ranked(finalIds.map { id -> ScoredItem(id, scoredMap[id] ?: 0f) })
    }

    private fun coldStartRanking(
        candidates: List<Long>,
        vectors: Map<Long, FloatArray>,
        topK: Int,
        cs: ClusterService,
        profile: UserProfile,
        seed: Long,
    ): List<Long> {
        val targetClusters = cs.epsilonCandidates(profile, k = topK, seed = seed)
        val byCluster: Map<Int, List<Long>> = candidates.groupBy { id -> cs.assignCluster(vectors.getValue(id)) }
        val queues = targetClusters.map { ArrayDeque(byCluster[it] ?: emptyList()) }
        val result = mutableListOf<Long>()
        while (result.size < topK) {
            val before = result.size
            queues.forEach { q -> if (q.isNotEmpty() && result.size < topK) result.add(q.removeFirst()) }
            if (result.size == before) break
        }
        return result
    }

    companion object {
        /** MMR relevance/diversity trade-off: 0 = max diversity, 1 = pure relevance. */
        const val MMR_LAMBDA = 0.5f

        private const val MILLIS_PER_DAY = 86_400_000L
    }
}
