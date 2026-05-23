package com.example

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

@Serializable
private data class ParseBody(
    val value: String,
)

class HealthRouteSmokeTest {
    @Test
    fun `GET health live returns 200`() =
        testApplication {
            application { configure(ReadinessGate().also { it.markReady() }) }
            assertEquals(HttpStatusCode.OK, client.get("/health/live").status)
        }

    @Test
    fun `GET health ready returns 503 before markReady and 200 after`() =
        testApplication {
            val gate = ReadinessGate()
            application { configure(gate) }

            assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/health/ready").status)
            gate.markReady()
            assertEquals(HttpStatusCode.OK, client.get("/health/ready").status)
        }

    @Test
    fun `invalid JSON body returns 400 with BAD_REQUEST code`() =
        testApplication {
            application {
                configure(ReadinessGate().also { it.markReady() })
                routing {
                    post("/test/parse") {
                        call.receive<ParseBody>()
                        call.respond(HttpStatusCode.OK)
                    }
                }
            }
            val response =
                client.post("/test/parse") {
                    contentType(ContentType.Application.Json)
                    setBody("{not valid json}")
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            val error = Json.decodeFromString<ErrorResponse>(response.bodyAsText())
            assertEquals("BAD_REQUEST", error.error.code)
        }

    @Test
    fun `unhandled exception is mapped to 500 with INTERNAL_ERROR code`() =
        testApplication {
            application {
                configure(ReadinessGate().also { it.markReady() })
                routing {
                    get("/test/boom") { error("synthetic boom") }
                }
            }
            val response = client.get("/test/boom")
            assertEquals(HttpStatusCode.InternalServerError, response.status)
            val error = Json.decodeFromString<ErrorResponse>(response.bodyAsText())
            assertEquals("INTERNAL_ERROR", error.error.code)
        }
}
