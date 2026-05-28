package com.example

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.example.application.embedding.CachingEmbeddingAdapter
import com.example.application.profile.CachingProfileAdapter
import com.example.domain.model.EmbeddingVersion
import com.example.domain.model.Prototype
import com.example.domain.model.UserProfile
import com.example.infrastructure.sqlite.ProfileRepository
import com.example.infrastructure.sqlite.initSchema
import com.example.routing.handlers.RankingHandler
import com.example.routing.routes.configureRankingRoutes
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.v1.jdbc.Database
import java.util.Date
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class RankingSmokeTest {
    private lateinit var db: Database

    private val secret = "ranking-smoke-secret-32-bytes-long"
    private val algo = Algorithm.HMAC256(secret)

    private fun token(sub: String): String =
        JWT
            .create()
            .withClaim("sub", sub)
            .withExpiresAt(Date(System.currentTimeMillis() + 60_000))
            .sign(algo)

    @BeforeTest
    fun setUp() {
        db =
            Database.connect(
                "jdbc:h2:mem:${System.nanoTime()};MODE=MySQL;DB_CLOSE_DELAY=-1",
                "org.h2.Driver",
            )
        initSchema(db)
        // Pre-insert a profile so embeddingVersion matches the active version in the handler.
        ProfileRepository(db).upsert(userId = USER_ID, embeddingVersion = "v1", lastAppliedEventId = 0L)
    }

    @AfterTest
    fun tearDown() {}

    private fun buildHandler(): RankingHandler {
        val profileRepo = ProfileRepository(db)
        val cachingProfile =
            CachingProfileAdapter(
                loadProfile = { userId ->
                    profileRepo.load(userId)?.let { row ->
                        UserProfile(
                            userId = row.userId,
                            embeddingVersion = EmbeddingVersion(row.embeddingVersion),
                            positivePrototypes = emptyList(),
                            negativePrototypes = emptyList(),
                            sessionVector = FloatArray(Prototype.VECTOR_DIM),
                            longTermVector = FloatArray(Prototype.VECTOR_DIM),
                            lastAppliedEventId = row.lastAppliedEventId,
                        )
                    }
                },
                loadEvents = { _, _ -> emptyList() },
                saveProfile = {},
            )
        val cachingEmbedding = CachingEmbeddingAdapter(lookupFromStore = { _ -> emptyMap() })
        return RankingHandler(
            cachingProfile = cachingProfile,
            cachingEmbedding = cachingEmbedding,
            clusterService = null,
            activeEmbeddingVersion = { EmbeddingVersion("v1") },
        )
    }

    private fun setup(block: suspend ApplicationTestBuilder.() -> Unit) =
        testApplication {
            application {
                configure(ReadinessGate().also { it.markReady() }, secret)
                configureRankingRoutes(buildHandler())
            }
            block()
        }

    @Test
    fun `no JWT returns 401`() =
        setup {
            assertEquals(HttpStatusCode.Unauthorized, client.post("/ranking/score").status)
        }

    @Test
    fun `JWT sub mismatch returns 403`() =
        setup {
            val response =
                client.post("/ranking/score") {
                    header(HttpHeaders.Authorization, "Bearer ${token("99")}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"userId":1,"candidateIds":[10],"topK":5}""")
                }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    @Test
    fun `valid JWT with empty catalog returns 200 with empty items`() =
        setup {
            val response =
                client.post("/ranking/score") {
                    header(HttpHeaders.Authorization, "Bearer ${token("$USER_ID")}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"userId":$USER_ID,"candidateIds":[10,20,30],"topK":5}""")
                }
            assertEquals(HttpStatusCode.OK, response.status)
        }

    @Test
    fun `empty candidateIds returns 422`() =
        setup {
            val response =
                client.post("/ranking/score") {
                    header(HttpHeaders.Authorization, "Bearer ${token("$USER_ID")}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"userId":$USER_ID,"candidateIds":[],"topK":5}""")
                }
            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        }

    companion object {
        private const val USER_ID = 1L
    }
}
