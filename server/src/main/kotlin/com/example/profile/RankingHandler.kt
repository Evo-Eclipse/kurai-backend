package com.example.profile

import com.example.ErrorDetail
import com.example.ErrorResponse
import com.example.application.embedding.CachingEmbeddingAdapter
import com.example.application.profile.CachingProfileAdapter
import com.example.domain.cluster.ClusterService
import com.example.domain.model.EmbeddingVersion
import com.example.domain.model.UserProfile
import com.example.domain.profile.Scoring
import com.example.domain.profile.mmr
import com.example.requireAuthenticatedUserId
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable

@Serializable
data class RankingRequest(
    val userId: Long,
    val candidateIds: List<Long>,
    val topK: Int,
)

@Serializable
data class ScoredItemResponse(
    val itemId: Long,
    val score: Float,
)

@Serializable
data class RankingResponse(
    val items: List<ScoredItemResponse>,
)

class RankingHandler(
    private val cachingProfile: CachingProfileAdapter,
    private val cachingEmbedding: CachingEmbeddingAdapter,
    private val getClusterService: () -> ClusterService?,
    private val activeEmbeddingVersion: () -> EmbeddingVersion,
) {
    suspend fun handleScore(call: ApplicationCall) {
        val sub = call.requireAuthenticatedUserId() ?: return
        val req = call.receive<RankingRequest>()
        if (sub != req.userId) {
            call.respond(HttpStatusCode.Forbidden, ErrorResponse(ErrorDetail("FORBIDDEN")))
            return
        }

        if (req.candidateIds.isEmpty() || req.candidateIds.size > MAX_CANDIDATES) {
            call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse(ErrorDetail("INVALID_CANDIDATE_IDS")))
            return
        }
        if (req.topK !in 1..MAX_TOP_K) {
            call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse(ErrorDetail("INVALID_TOP_K")))
            return
        }

        val profile = cachingProfile.getOrLoad(req.userId)

        if (profile.embeddingVersion != activeEmbeddingVersion()) {
            call.response.headers.append(HttpHeaders.RetryAfter, "30")
            call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse(ErrorDetail("EMBEDDING_VERSION_MISMATCH")))
            return
        }

        val vecs = cachingEmbedding.lookupVectors(req.candidateIds)
        val scored = req.candidateIds.mapNotNull { id -> vecs[id]?.let { id to Scoring.score(profile, it) } }
        val scoredMap: Map<Long, Float> = scored.toMap()

        val clusterService = getClusterService()
        val finalIds: List<Long> =
            when {
                profile.positivePrototypes.isNotEmpty() ->
                    scored.mmr(vecs, lambda = 0.5f, n = req.topK).distinct().take(req.topK)
                clusterService != null -> {
                    // Cold-start: no positive prototypes yet — distribute across target clusters.
                    // Seed combines userId with a daily bucket so the ordering is stable within
                    // a session but rotates each day and differs across users.
                    val seed = req.userId xor (System.currentTimeMillis() / 86_400_000L)
                    coldStartRanking(
                        req.candidateIds.filter { it in vecs },
                        vecs,
                        minOf(req.topK, clusterService.size),
                        clusterService,
                        profile,
                        seed,
                    )
                }
                else -> req.candidateIds.take(req.topK)
            }

        call.respond(
            RankingResponse(
                items =
                    finalIds.map { id ->
                        ScoredItemResponse(id, scoredMap[id] ?: 0f)
                    },
            ),
        )
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
        const val MAX_CANDIDATES = 500
        const val MAX_TOP_K = 100
    }
}
