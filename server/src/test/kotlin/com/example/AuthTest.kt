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
import kotlin.test.assertFalse

class AuthTest {
    private val secret = "test-secret-32-bytes-long-enough!"
    private val algo = Algorithm.HMAC256(secret)

    private fun validToken(sub: String = "42"): String =
        JWT
            .create()
            .withClaim("sub", sub)
            .withExpiresAt(Date(System.currentTimeMillis() + 60_000))
            .sign(algo)

    private fun expiredToken(): String =
        JWT
            .create()
            .withClaim("sub", "42")
            .withExpiresAt(Date(System.currentTimeMillis() - 1_000))
            .sign(algo)

    private fun wrongSignatureToken(): String =
        JWT
            .create()
            .withClaim("sub", "42")
            .withExpiresAt(Date(System.currentTimeMillis() + 60_000))
            .sign(Algorithm.HMAC256("wrong-secret-different-key!!!!"))

    private fun blankSubToken(): String =
        JWT
            .create()
            .withClaim("sub", "  ")
            .withExpiresAt(Date(System.currentTimeMillis() + 60_000))
            .sign(algo)

    private fun appWithTestRoute(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) =
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
    fun `valid JWT returns 200`() =
        appWithTestRoute {
            val response =
                client.get("/auth-test") {
                    header(HttpHeaders.Authorization, "Bearer ${validToken()}")
                }
            assertEquals(HttpStatusCode.OK, response.status)
        }

    @Test
    fun `no Authorization header returns 401`() =
        appWithTestRoute {
            val response = client.get("/auth-test")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `expired token returns 401`() =
        appWithTestRoute {
            val response =
                client.get("/auth-test") {
                    header(HttpHeaders.Authorization, "Bearer ${expiredToken()}")
                }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `wrong signature returns 401`() =
        appWithTestRoute {
            val response =
                client.get("/auth-test") {
                    header(HttpHeaders.Authorization, "Bearer ${wrongSignatureToken()}")
                }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `blank sub claim returns 401`() =
        appWithTestRoute {
            val response =
                client.get("/auth-test") {
                    header(HttpHeaders.Authorization, "Bearer ${blankSubToken()}")
                }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `log output does not contain Authorization header value`() {
        val logCapture = StringBuilder()
        val appender =
            object : ch.qos.logback.core.AppenderBase<ch.qos.logback.classic.spi.ILoggingEvent>() {
                override fun append(event: ch.qos.logback.classic.spi.ILoggingEvent) {
                    logCapture.append(event.formattedMessage)
                }
            }
        val rootLogger =
            org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
                as ch.qos.logback.classic.Logger
        appender.start()
        rootLogger.addAppender(appender)
        try {
            appWithTestRoute {
                client.get("/auth-test") {
                    header(HttpHeaders.Authorization, "Bearer ${validToken()}")
                }
            }
        } finally {
            rootLogger.detachAppender(appender)
        }
        assertFalse(
            logCapture.contains("Bearer ey"),
            "Log output must not contain Authorization header value",
        )
    }
}
