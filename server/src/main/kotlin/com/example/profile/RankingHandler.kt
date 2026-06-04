package com.example.profile

import com.example.ErrorDetail
import com.example.ErrorResponse
import com.example.application.profile.RankingOutcome
import com.example.application.profile.RankingService
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

/**
 * Thin HTTP adapter over [RankingService]: authenticates, validates the
 * request shape, then maps the service's [RankingOutcome] to a response.
 * All ranking policy lives in [RankingService].
 */
class RankingHandler(
    private val rankingService: RankingService,
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

        when (val outcome = rankingService.rank(req.userId, req.candidateIds, req.topK)) {
            is RankingOutcome.Ranked ->
                call.respond(
                    RankingResponse(items = outcome.items.map { ScoredItemResponse(it.itemId, it.score) }),
                )

            RankingOutcome.VersionMismatch -> {
                call.response.headers.append(HttpHeaders.RetryAfter, "30")
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    ErrorResponse(ErrorDetail("EMBEDDING_VERSION_MISMATCH")),
                )
            }
        }
    }

    companion object {
        const val MAX_CANDIDATES = 500
        const val MAX_TOP_K = 100
    }
}
