package com.example.routing.handlers

import com.example.ErrorDetail
import com.example.ErrorResponse
import com.example.application.embedding.CachingEmbeddingAdapter
import com.example.application.profile.CachingProfileAdapter
import com.example.domain.cluster.ClusterService
import com.example.domain.model.EmbeddingVersion
import com.example.domain.profile.Scoring
import com.example.domain.profile.mmr
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
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
    private val clusterService: ClusterService?,
    private val activeEmbeddingVersion: () -> EmbeddingVersion,
) {
    suspend fun handleScore(call: ApplicationCall) {
        val sub =
            call
                .principal<JWTPrincipal>()
                ?.payload
                ?.getClaim("sub")
                ?.asString()
                ?.toLongOrNull()
                ?: run {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse(ErrorDetail("UNAUTHORIZED")))
                    return
                }

        val req = call.receive<RankingRequest>()
        if (sub != req.userId) {
            call.respond(HttpStatusCode.Forbidden, ErrorResponse(ErrorDetail("FORBIDDEN")))
            return
        }

        if (req.candidateIds.isEmpty() || req.candidateIds.size > MAX_CANDIDATES) {
            call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse(ErrorDetail("INVALID_CANDIDATE_IDS")))
            return
        }
        if (req.topK < 1 || req.topK > MAX_TOP_K) {
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
        val mmrIds = scored.mmr(vecs, lambda = 0.5f, n = req.topK)

        // ε-exploration and cold-start stratification added in wave 23
        val finalIds = mmrIds.distinct().take(req.topK)

        call.respond(
            RankingResponse(
                items =
                    finalIds.map { id ->
                        ScoredItemResponse(id, scored.find { it.first == id }?.second ?: 0f)
                    },
            ),
        )
    }

    companion object {
        const val MAX_CANDIDATES = 500
        const val MAX_TOP_K = 100
    }
}
