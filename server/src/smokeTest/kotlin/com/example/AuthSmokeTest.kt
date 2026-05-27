package com.example

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.get
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
}
