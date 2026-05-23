package com.example

import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

@Serializable
data class ErrorResponse(
    val error: ErrorDetail,
)

@Serializable
data class ErrorDetail(
    val code: String,
)

private val log = LoggerFactory.getLogger("com.example.ErrorMapping")

fun StatusPagesConfig.errorMapping() {
    exception<BadRequestException> { call, _ ->
        call.respond(HttpStatusCode.BadRequest, ErrorResponse(ErrorDetail("BAD_REQUEST")))
    }
    // Fallback: any unhandled throwable becomes a structured 500. Prevents
    // raw stack traces from leaking through the response on failures in
    // downstream waves (infra-sqlite, ranking, etc.).
    exception<Throwable> { call, cause ->
        log.error("Unhandled exception while processing request", cause)
        call.respond(HttpStatusCode.InternalServerError, ErrorResponse(ErrorDetail("INTERNAL_ERROR")))
    }
}
