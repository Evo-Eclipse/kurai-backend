package com.example

import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable

@Serializable
data class ErrorResponse(
    val error: ErrorDetail,
)

@Serializable
data class ErrorDetail(
    val code: String,
)

fun StatusPagesConfig.errorMapping() {
    exception<BadRequestException> { call, _ ->
        call.respond(HttpStatusCode.BadRequest, ErrorResponse(ErrorDetail("BAD_REQUEST")))
    }
}
