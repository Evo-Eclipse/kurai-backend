package com.example

import com.example.application.auth.AuthService
import com.example.application.auth.MagicLinkSender
import com.example.auth.ChallengeIpRateLimiter
import com.example.auth.FixedWindowRateLimiter
import com.example.infrastructure.sqlite.AuthIdentityRepository
import com.example.infrastructure.sqlite.AuthSessionRepository
import com.example.infrastructure.sqlite.LoginChallengeRepository
import com.example.infrastructure.sqlite.UserRepository
import com.example.infrastructure.sqlite.initSchema
import com.example.routing.SessionAuthenticator
import com.example.routing.handlers.AuthHandler
import com.example.routing.handlers.ChallengeRequest
import com.example.routing.handlers.ChallengeResponse
import com.example.routing.handlers.RefreshRequest
import com.example.routing.handlers.RefreshResponse
import com.example.routing.handlers.VerifyRequest
import com.example.routing.handlers.VerifyResponse
import com.example.routing.routes.configureAuthRoutes
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.v1.jdbc.Database
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation

class AuthFlowTest {
    private val secret = "test-secret-long-enough-for-hmac-256-bytes!"

    /**
     * Captures the OTP per email so a test can replay it. In production
     * the channel is e-mail; here it's an in-memory side table.
     */
    private class CapturingSender : MagicLinkSender {
        val byEmail = ConcurrentHashMap<String, Pair<String, String>>() // email -> (challengeId, code)

        override suspend fun send(
            email: String,
            challengeId: String,
            code: String,
        ) {
            byEmail[email] = challengeId to code
        }
    }

    private fun fresh(): Triple<Database, CapturingSender, AuthService> {
        val db = Database.connect("jdbc:h2:mem:auth${System.nanoTime()};MODE=MySQL;DB_CLOSE_DELAY=-1", "org.h2.Driver")
        initSchema(db)
        val sender = CapturingSender()
        val authService =
            AuthService(
                users = UserRepository(db),
                identities = AuthIdentityRepository(db),
                sessions = AuthSessionRepository(db),
                challenges = LoginChallengeRepository(db),
                sender = sender,
                challengeTtlMs = 10 * 60 * 1000L,
                sessionTtlMs = 30L * 24 * 60 * 60 * 1000L,
            )
        return Triple(db, sender, authService)
    }

    private fun ApplicationTestBuilder.installAuth(authService: AuthService) {
        application {
            configure(ReadinessGate().also { it.markReady() }, secret)
            val sessionAuth = SessionAuthenticator(authService, cacheTtl = Duration.ofMillis(1))
            val handler =
                AuthHandler(
                    authService = authService,
                    sessionAuth = sessionAuth,
                    challengeIpRateLimiter =
                        ChallengeIpRateLimiter(
                            FixedWindowRateLimiter(
                                maxPerWindow = { AuthService.DEFAULT_CHALLENGE_RATE_LIMIT_MAX },
                                windowMs = { AuthService.DEFAULT_CHALLENGE_RATE_LIMIT_WINDOW_MS },
                            ),
                        ),
                    jwtSecret = secret,
                    jwtTtlMs = 60_000L,
                )
            configureAuthRoutes(handler)
        }
    }

    private fun ApplicationTestBuilder.jsonClient() =
        createClient {
            install(ClientContentNegotiation) { json() }
        }

    @Test
    fun `full happy path — challenge issues code, verify mints JWT, logout revokes session`() =
        testApplication {
            val (_, sender, authService) = fresh()
            installAuth(authService)
            val http = jsonClient()

            val challenge: ChallengeResponse =
                http
                    .post("/auth/challenge") {
                        contentType(ContentType.Application.Json)
                        setBody(ChallengeRequest(email = "alice@example.com"))
                    }.body()
            val (capturedId, code) =
                checkNotNull(sender.byEmail["alice@example.com"]) { "sender did not receive OTP" }
            assertEquals(challenge.challengeId, capturedId)

            val verify: VerifyResponse =
                http
                    .post("/auth/verify") {
                        contentType(ContentType.Application.Json)
                        setBody(VerifyRequest(challengeId = challenge.challengeId, code = code))
                    }.body()
            assertTrue(verify.userId > 0)
            assertTrue(verify.jwt.isNotBlank())
            assertTrue(verify.refreshToken.isNotBlank())

            assertTrue(authService.isSessionActive(verify.sessionId))

            val logout =
                http.post("/auth/logout") {
                    header(HttpHeaders.Authorization, "Bearer ${verify.jwt}")
                }
            assertEquals(HttpStatusCode.NoContent, logout.status)
            assertEquals(false, authService.isSessionActive(verify.sessionId))
        }

    @Test
    fun `verify with wrong code returns 401 and leaves no session`() =
        testApplication {
            val (_, sender, authService) = fresh()
            installAuth(authService)
            val http = jsonClient()

            http
                .post("/auth/challenge") {
                    contentType(ContentType.Application.Json)
                    setBody(ChallengeRequest(email = "bob@example.com"))
                }.body<ChallengeResponse>()
            val (challengeId, _) = checkNotNull(sender.byEmail["bob@example.com"])

            val resp =
                http.post("/auth/verify") {
                    contentType(ContentType.Application.Json)
                    setBody(VerifyRequest(challengeId = challengeId, code = "000000"))
                }
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
        }

    @Test
    fun `consumed challenge cannot be replayed`() =
        testApplication {
            val (_, sender, authService) = fresh()
            installAuth(authService)
            val http = jsonClient()

            http
                .post("/auth/challenge") {
                    contentType(ContentType.Application.Json)
                    setBody(ChallengeRequest(email = "carol@example.com"))
                }.body<ChallengeResponse>()
            val (challengeId, code) = checkNotNull(sender.byEmail["carol@example.com"])

            http
                .post("/auth/verify") {
                    contentType(ContentType.Application.Json)
                    setBody(VerifyRequest(challengeId = challengeId, code = code))
                }.body<VerifyResponse>()

            val replay =
                http.post("/auth/verify") {
                    contentType(ContentType.Application.Json)
                    setBody(VerifyRequest(challengeId = challengeId, code = code))
                }
            assertEquals(HttpStatusCode.Unauthorized, replay.status)
        }

    @Test
    fun `invalid email shape returns 422 INVALID_EMAIL`() =
        testApplication {
            val (_, _, authService) = fresh()
            installAuth(authService)
            val http = jsonClient()

            val resp =
                http.post("/auth/challenge") {
                    contentType(ContentType.Application.Json)
                    setBody(ChallengeRequest(email = "not-an-email"))
                }
            assertEquals(HttpStatusCode.UnprocessableEntity, resp.status)
        }

    @Test
    fun `refresh returns a fresh JWT for the same session`() =
        testApplication {
            val (_, sender, authService) = fresh()
            installAuth(authService)
            val http = jsonClient()

            http
                .post("/auth/challenge") {
                    contentType(ContentType.Application.Json)
                    setBody(ChallengeRequest(email = "dave@example.com"))
                }.body<ChallengeResponse>()
            val (challengeId, code) = checkNotNull(sender.byEmail["dave@example.com"])
            val first: VerifyResponse =
                http
                    .post("/auth/verify") {
                        contentType(ContentType.Application.Json)
                        setBody(VerifyRequest(challengeId = challengeId, code = code))
                    }.body()

            // Sleep just enough so the issuedAt second of the new JWT differs.
            Thread.sleep(1_100)
            val refreshed: RefreshResponse =
                http
                    .post("/auth/refresh") {
                        contentType(ContentType.Application.Json)
                        setBody(RefreshRequest(sessionId = first.sessionId, refreshToken = first.refreshToken))
                    }.body()
            assertTrue(refreshed.jwt.isNotBlank())
            assertNotEquals(first.jwt, refreshed.jwt)
        }

    @Test
    fun `refresh with wrong token returns 401`() =
        testApplication {
            val (_, sender, authService) = fresh()
            installAuth(authService)
            val http = jsonClient()

            http
                .post("/auth/challenge") {
                    contentType(ContentType.Application.Json)
                    setBody(ChallengeRequest(email = "erin@example.com"))
                }.body<ChallengeResponse>()
            val (challengeId, code) = checkNotNull(sender.byEmail["erin@example.com"])
            val first: VerifyResponse =
                http
                    .post("/auth/verify") {
                        contentType(ContentType.Application.Json)
                        setBody(VerifyRequest(challengeId = challengeId, code = code))
                    }.body()

            val resp =
                http.post("/auth/refresh") {
                    contentType(ContentType.Application.Json)
                    setBody(RefreshRequest(sessionId = first.sessionId, refreshToken = "deadbeef"))
                }
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
        }
}
