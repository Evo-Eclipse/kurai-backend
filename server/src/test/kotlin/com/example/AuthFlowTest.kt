package com.example

import com.example.application.auth.AuthService
import com.example.application.auth.MagicLinkSender
import com.example.auth.AuthHandler
import com.example.auth.ChallengeIpRateLimiter
import com.example.auth.ChallengeRequest
import com.example.auth.ChallengeResponse
import com.example.auth.FixedWindowRateLimiter
import com.example.auth.LegacyVerifyRequest
import com.example.auth.RefreshRequest
import com.example.auth.RefreshResponse
import com.example.auth.SessionAuthenticator
import com.example.auth.VerifyRequest
import com.example.auth.VerifyResponse
import com.example.auth.configureAuthRoutes
import com.example.infrastructure.sqlite.AuthIdentityRepository
import com.example.infrastructure.sqlite.AuthSessionRepository
import com.example.infrastructure.sqlite.LoginChallengeRepository
import com.example.infrastructure.sqlite.UserRepository
import com.example.infrastructure.sqlite.initSchema
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
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
                challengeTtlMs = { 10 * 60 * 1000L },
                sessionTtlMs = { 30L * 24 * 60 * 60 * 1000L },
            )
        return Triple(db, sender, authService)
    }

    private fun ApplicationTestBuilder.installAuth(authService: AuthService) {
        application {
            // 1 ms cache TTL so the central revocation check reflects DB
            // state immediately (no stale window masking logout in tests).
            val sessionAuth = SessionAuthenticator(authService, cacheTtl = Duration.ofMillis(1))
            configure(ReadinessGate().also { it.markReady() }, secret) { sessionAuth.isActive(it) }
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
            // A stand-in resource endpoint guarded only by `authenticate`,
            // to prove revocation reaches routes that never call the
            // SessionAuthenticator themselves.
            routing {
                authenticate("kurai") {
                    get("/protected") { call.respond(HttpStatusCode.OK) }
                }
            }
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

            val refreshed: RefreshResponse =
                http
                    .post("/auth/refresh") {
                        contentType(ContentType.Application.Json)
                        setBody(RefreshRequest(sessionId = first.sessionId, refreshToken = first.refreshToken))
                    }.body()
            assertTrue(refreshed.jwt.isNotBlank())
            assertNotEquals(first.jwt, refreshed.jwt)
            // Refresh rotates: a fresh session id and secret come back.
            assertNotEquals(first.sessionId, refreshed.sessionId)
            assertNotEquals(first.refreshToken, refreshed.refreshToken)
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

    @Test
    fun `logout revokes access to a protected resource route, not just logout itself`() =
        testApplication {
            val (_, sender, authService) = fresh()
            installAuth(authService)
            val http = jsonClient()

            http
                .post("/auth/challenge") {
                    contentType(ContentType.Application.Json)
                    setBody(ChallengeRequest(email = "frank@example.com"))
                }.body<ChallengeResponse>()
            val (challengeId, code) = checkNotNull(sender.byEmail["frank@example.com"])
            val verify: VerifyResponse =
                http
                    .post("/auth/verify") {
                        contentType(ContentType.Application.Json)
                        setBody(VerifyRequest(challengeId = challengeId, code = code))
                    }.body()

            // The JWT works on a resource route before logout …
            val before =
                http.get("/protected") { header(HttpHeaders.Authorization, "Bearer ${verify.jwt}") }
            assertEquals(HttpStatusCode.OK, before.status)

            http.post("/auth/logout") { header(HttpHeaders.Authorization, "Bearer ${verify.jwt}") }

            // … and the same JWT is rejected after, even though /protected
            // never calls the SessionAuthenticator itself.
            val after =
                http.get("/protected") { header(HttpHeaders.Authorization, "Bearer ${verify.jwt}") }
            assertEquals(HttpStatusCode.Unauthorized, after.status)
        }

    @Test
    fun `too many wrong codes lock the challenge even for the correct code`() =
        testApplication {
            val (_, sender, authService) = fresh()
            installAuth(authService)
            val http = jsonClient()

            http
                .post("/auth/challenge") {
                    contentType(ContentType.Application.Json)
                    setBody(ChallengeRequest(email = "grace@example.com"))
                }.body<ChallengeResponse>()
            val (challengeId, code) = checkNotNull(sender.byEmail["grace@example.com"])
            val wrong = if (code == "000000") "000001" else "000000"

            repeat(AuthService.MAX_VERIFY_ATTEMPTS) {
                val resp =
                    http.post("/auth/verify") {
                        contentType(ContentType.Application.Json)
                        setBody(VerifyRequest(challengeId = challengeId, code = wrong))
                    }
                assertEquals(HttpStatusCode.Unauthorized, resp.status)
            }

            // Budget spent — the correct code no longer works.
            val locked =
                http.post("/auth/verify") {
                    contentType(ContentType.Application.Json)
                    setBody(VerifyRequest(challengeId = challengeId, code = code))
                }
            assertEquals(HttpStatusCode.Unauthorized, locked.status)
        }

    @Test
    fun `replaying a rotated refresh token is treated as theft and burns the chain`() =
        testApplication {
            val (_, sender, authService) = fresh()
            installAuth(authService)
            val http = jsonClient()

            http
                .post("/auth/challenge") {
                    contentType(ContentType.Application.Json)
                    setBody(ChallengeRequest(email = "heidi@example.com"))
                }.body<ChallengeResponse>()
            val (challengeId, code) = checkNotNull(sender.byEmail["heidi@example.com"])
            val first: VerifyResponse =
                http
                    .post("/auth/verify") {
                        contentType(ContentType.Application.Json)
                        setBody(VerifyRequest(challengeId = challengeId, code = code))
                    }.body()

            // Legitimate rotation: old token -> new pair.
            val rotated: RefreshResponse =
                http
                    .post("/auth/refresh") {
                        contentType(ContentType.Application.Json)
                        setBody(RefreshRequest(sessionId = first.sessionId, refreshToken = first.refreshToken))
                    }.body()

            // Replaying the now-superseded token is detected as reuse.
            val replay =
                http.post("/auth/refresh") {
                    contentType(ContentType.Application.Json)
                    setBody(RefreshRequest(sessionId = first.sessionId, refreshToken = first.refreshToken))
                }
            assertEquals(HttpStatusCode.Unauthorized, replay.status)

            // …and the whole chain is burned: even the freshly rotated token dies.
            val afterBurn =
                http.post("/auth/refresh") {
                    contentType(ContentType.Application.Json)
                    setBody(RefreshRequest(sessionId = rotated.sessionId, refreshToken = rotated.refreshToken))
                }
            assertEquals(HttpStatusCode.Unauthorized, afterBurn.status)
        }

    @Test
    fun `legacy key logs in and a disabled key is rejected`() =
        testApplication {
            val (_, _, authService) = fresh()
            installAuth(authService)
            val http = jsonClient()

            val issued = authService.issueLegacyKey()

            val login: VerifyResponse =
                http
                    .post("/auth/legacy/verify") {
                        contentType(ContentType.Application.Json)
                        setBody(LegacyVerifyRequest(key = issued.key))
                    }.body()
            assertEquals(issued.userId, login.userId)
            val protectedOk =
                http.get("/protected") { header(HttpHeaders.Authorization, "Bearer ${login.jwt}") }
            assertEquals(HttpStatusCode.OK, protectedOk.status)

            // Retire the key — subsequent logins are refused.
            authService.disableLegacyKey(issued.key)
            val afterDisable =
                http.post("/auth/legacy/verify") {
                    contentType(ContentType.Application.Json)
                    setBody(LegacyVerifyRequest(key = issued.key))
                }
            assertEquals(HttpStatusCode.Unauthorized, afterDisable.status)
        }

    @Test
    fun `unknown legacy key is rejected`() =
        testApplication {
            val (_, _, authService) = fresh()
            installAuth(authService)
            val http = jsonClient()

            val resp =
                http.post("/auth/legacy/verify") {
                    contentType(ContentType.Application.Json)
                    setBody(LegacyVerifyRequest(key = "00000000-0000-4000-8000-000000000000"))
                }
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
        }
}
