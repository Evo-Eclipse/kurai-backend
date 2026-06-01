package com.example.ingestion

import com.example.ErrorDetail
import com.example.ErrorResponse
import com.example.application.embedding.CachingEmbeddingAdapter
import com.example.application.profile.CachingProfileAdapter
import com.example.domain.events.EventQueue
import com.example.domain.events.RawEvent
import com.example.domain.model.EmbeddingVersion
import com.example.domain.model.UserEvent
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable

@Serializable
data class IngestionRequest(
    val userId: Long,
    val itemId: Long,
    val sourceTag: String,
)

class IngestionHandler(
    private val cachingProfile: CachingProfileAdapter,
    private val cachingEmbedding: CachingEmbeddingAdapter,
    private val eventQueue: EventQueue,
    private val activeEmbeddingVersion: () -> EmbeddingVersion,
    /** Resolves an opaque source tag to its numeric weight (dictionary + default). */
    private val resolveWeight: (String) -> Float,
) {
    suspend fun handleIngest(call: ApplicationCall) {
        val principal =
            call.principal<JWTPrincipal>()
                ?: run {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse(ErrorDetail("UNAUTHORIZED")))
                    return
                }

        val sub =
            principal.payload
                .getClaim("sub")
                .asString()
                .toLongOrNull()
                ?: run {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse(ErrorDetail("UNAUTHORIZED")))
                    return
                }

        val req = call.receive<IngestionRequest>()
        if (sub != req.userId) {
            call.respond(HttpStatusCode.Forbidden, ErrorResponse(ErrorDetail("FORBIDDEN")))
            return
        }

        val vectors = cachingEmbedding.lookupVectors(listOf(req.itemId))
        val vector =
            vectors[req.itemId]
                ?: run {
                    call.respond(
                        HttpStatusCode.UnprocessableEntity,
                        ErrorResponse(ErrorDetail("ITEM_NOT_INDEXED")),
                    )
                    return
                }

        val activeVersion = activeEmbeddingVersion()
        val event =
            try {
                UserEvent(
                    id = 0L,
                    userId = req.userId,
                    itemId = req.itemId,
                    weight = resolveWeight(req.sourceTag),
                    embeddingVersion = activeVersion,
                    ts = System.currentTimeMillis(),
                )
            } catch (e: IllegalArgumentException) {
                throw BadRequestException(e.message ?: "Invalid request")
            }

        // Live update uses the resolved weight; storage keeps the raw tag so a
        // later weight backfill is reflected on the next full recompute.
        cachingProfile.update(req.userId, event, vector)
        eventQueue.enqueue(
            RawEvent(
                userId = req.userId,
                itemId = req.itemId,
                sourceTag = req.sourceTag,
                embeddingVersion = activeVersion,
            ),
        )

        call.respond(HttpStatusCode.NoContent)
    }
}
