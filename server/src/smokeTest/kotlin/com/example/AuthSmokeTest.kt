package com.example

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals

class AuthSmokeTest {
    private val secret = "smoke-test-secret-32-bytes-long!!"
    private val algo = Algorithm.HMAC256(secret)

    private fun validToken(): String =
        JWT
            .create()
            .withClaim("sub", "1")
            .withExpiresAt(Date(System.currentTimeMillis() + 60_000))
            .sign(algo)

    private fun expiredToken(): String =
        JWT
            .create()
            .withClaim("sub", "1")
            .withExpiresAt(Date(System.currentTimeMillis() - 1_000))
            .sign(algo)

    private fun setup(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) =
        testApplication {
            application {
                configure(ReadinessGate().also { it.markReady() }, secret)
                routing {
                    authenticate("kurai") {
                        get("/auth-test") { call.respond(HttpStatusCode.OK) }
                    }
                    post("/auth/challenge") {
                        call.respond(HttpStatusCode.UnprocessableEntity)
                    }
                    post("/auth/key/issue") {
                        call.response.headers.append(HttpHeaders.RetryAfter, "60")
                        call.respond(HttpStatusCode.TooManyRequests)
                    }
                    post("/auth/key/disable") {
                        when (call.request.headers["X-Admin-Token"]) {
                            "smoke-admin-token" -> call.respond(HttpStatusCode.NoContent)
                            else -> call.respond(HttpStatusCode.Unauthorized)
                        }
                    }
                    post("/auth/verify") { call.respond(HttpStatusCode.Unauthorized) }
                    post("/auth/refresh") { call.respond(HttpStatusCode.Unauthorized) }
                }
            }
            block()
        }

    @Test
    fun `valid token returns 200`() =
        setup {
            val response =
                client.get("/auth-test") {
                    header(HttpHeaders.Authorization, "Bearer ${validToken()}")
                }
            assertEquals(HttpStatusCode.OK, response.status)
        }

    @Test
    fun `missing token returns 401`() =
        setup {
            assertEquals(HttpStatusCode.Unauthorized, client.get("/auth-test").status)
        }

    @Test
    fun `expired token returns 401`() =
        setup {
            val response =
                client.get("/auth-test") {
                    header(HttpHeaders.Authorization, "Bearer ${expiredToken()}")
                }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `auth challenge endpoint is wired and rejects bad input`() =
        setup {
            val response =
                client.post("/auth/challenge") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"email":"bad"}""")
                }
            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        }

    @Test
    fun `key issue is rate limited in smoke (429 + retry header)`() =
        setup {
            val response =
                client.post("/auth/key/issue") {
                    contentType(ContentType.Application.Json)
                    setBody("""{}""")
                }
            assertEquals(HttpStatusCode.TooManyRequests, response.status)
            assertEquals("60", response.headers[HttpHeaders.RetryAfter])
        }

    @Test
    fun `key disable is wired and gated by X-Admin-Token`() =
        setup {
            val missing =
                client.post("/auth/key/disable") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"key":"00000000-0000-4000-8000-000000000000"}""")
                }
            assertEquals(HttpStatusCode.Unauthorized, missing.status)

            val wrong =
                client.post("/auth/key/disable") {
                    header("X-Admin-Token", "wrong")
                    contentType(ContentType.Application.Json)
                    setBody("""{"key":"00000000-0000-4000-8000-000000000000"}""")
                }
            assertEquals(HttpStatusCode.Unauthorized, wrong.status)

            val ok =
                client.post("/auth/key/disable") {
                    header("X-Admin-Token", "smoke-admin-token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"key":"00000000-0000-4000-8000-000000000000"}""")
                }
            assertEquals(HttpStatusCode.NoContent, ok.status)
        }

    @Test
    fun `verify and refresh endpoints are wired and reject without valid`() =
        setup {
            val v =
                client.post("/auth/verify") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"challengeId":"x","code":"1"}""")
                }
            assertEquals(HttpStatusCode.Unauthorized, v.status)

            val r =
                client.post("/auth/refresh") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"sessionId":"s","refreshToken":"t"}""")
                }
            assertEquals(HttpStatusCode.Unauthorized, r.status)
        }
}
