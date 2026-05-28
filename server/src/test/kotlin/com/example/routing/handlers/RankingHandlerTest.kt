package com.example.routing.handlers

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.example.ReadinessGate
import com.example.application.embedding.CachingEmbeddingAdapter
import com.example.application.profile.CachingProfileAdapter
import com.example.configure
import com.example.domain.model.EmbeddingVersion
import com.example.domain.model.Prototype
import com.example.domain.model.UserProfile
import com.example.domain.profile.Scoring
import com.example.routing.routes.configureRankingRoutes
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RankingHandlerTest {
    private val secret = "ranking-test-secret-32-bytes-long"
    private val algo = Algorithm.HMAC256(secret)

    private fun token(sub: String): String =
        JWT
            .create()
            .withClaim("sub", sub)
            .withExpiresAt(Date(System.currentTimeMillis() + 60_000))
            .sign(algo)

    private fun normalVec(seed: Int): FloatArray =
        Scoring.l2Normalize(
            FloatArray(Prototype.VECTOR_DIM) {
                (it + seed + 1).toFloat()
            },
        )

    private fun basisVec(dim: Int): FloatArray {
        val v = FloatArray(Prototype.VECTOR_DIM)
        v[dim] = 1f
        return v
    }

    private fun profileAdapter(profile: UserProfile) =
        CachingProfileAdapter(
            loadProfile = { _ -> profile },
            loadEvents = { _, _ -> emptyList() },
            saveProfile = {},
        )

    private fun embeddingAdapter(vectors: Map<Long, FloatArray>) =
        CachingEmbeddingAdapter(lookupFromStore = { ids ->
            ids.mapNotNull { id -> vectors[id]?.let { id to it } }.toMap()
        })

    private fun warmProfile(version: String = "v1"): UserProfile {
        val proto1 = Prototype(basisVec(0), 1f)
        val proto2 = Prototype(basisVec(1), 1f)
        val proto3 = Prototype(basisVec(2), 1f)
        return UserProfile(
            userId = 1L,
            embeddingVersion = EmbeddingVersion(version),
            positivePrototypes = listOf(proto1, proto2, proto3),
            negativePrototypes = emptyList(),
            sessionVector = FloatArray(Prototype.VECTOR_DIM),
            longTermVector = FloatArray(Prototype.VECTOR_DIM),
            lastAppliedEventId = 0L,
        )
    }

    private fun coldProfile(): UserProfile =
        UserProfile(
            userId = 1L,
            embeddingVersion = EmbeddingVersion("v1"),
            positivePrototypes = emptyList(),
            negativePrototypes = emptyList(),
            sessionVector = FloatArray(Prototype.VECTOR_DIM),
            longTermVector = FloatArray(Prototype.VECTOR_DIM),
            lastAppliedEventId = 0L,
        )

    @Test
    fun `profile with prototypes ranks aligned candidates highest`() {
        val vecs =
            (1L..10L).associate { id ->
                id to if (id <= 3L) basisVec((id - 1).toInt()) else normalVec(id.toInt())
            }
        val handler =
            RankingHandler(
                profileAdapter(warmProfile()),
                embeddingAdapter(vecs),
                clusterService = null,
                activeEmbeddingVersion = { EmbeddingVersion("v1") },
            )
        testApplication {
            application {
                configure(ReadinessGate().also { it.markReady() }, secret)
                configureRankingRoutes(handler)
            }
            val response =
                client.post("/ranking/score") {
                    header(HttpHeaders.Authorization, "Bearer ${token("1")}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"userId":1,"candidateIds":[1,2,3,4,5,6,7,8,9,10],"topK":3}""")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = Json.decodeFromString<RankingResponse>(response.bodyAsText())
            assertEquals(3, body.items.size)
            // Items 1, 2, 3 are aligned with the positive prototypes; they must all appear.
            val returned = body.items.map { it.itemId }.toSet()
            assertTrue(1L in returned && 2L in returned && 3L in returned)
        }
    }

    @Test
    fun `stale embedding version returns 503 with Retry-After header`() {
        val handler =
            RankingHandler(
                profileAdapter(warmProfile("v1")),
                embeddingAdapter(emptyMap()),
                clusterService = null,
                activeEmbeddingVersion = { EmbeddingVersion("v2") },
            )
        testApplication {
            application {
                configure(ReadinessGate().also { it.markReady() }, secret)
                configureRankingRoutes(handler)
            }
            val response =
                client.post("/ranking/score") {
                    header(HttpHeaders.Authorization, "Bearer ${token("1")}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"userId":1,"candidateIds":[1],"topK":1}""")
                }
            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertNotNull(response.headers[HttpHeaders.RetryAfter])
        }
    }

    @Test
    fun `empty candidateIds returns 422`() =
        rankingTest(warmProfile(), emptyMap()) { client ->
            val response =
                client.post("/ranking/score") {
                    header(HttpHeaders.Authorization, "Bearer ${token("1")}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"userId":1,"candidateIds":[],"topK":5}""")
                }
            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        }

    @Test
    fun `candidateIds size 501 returns 422`() =
        rankingTest(warmProfile(), emptyMap()) { client ->
            val ids = (1..501).joinToString(",")
            val response =
                client.post("/ranking/score") {
                    header(HttpHeaders.Authorization, "Bearer ${token("1")}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"userId":1,"candidateIds":[$ids],"topK":5}""")
                }
            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        }

    @Test
    fun `topK 0 returns 422`() =
        rankingTest(warmProfile(), emptyMap()) { client ->
            val response =
                client.post("/ranking/score") {
                    header(HttpHeaders.Authorization, "Bearer ${token("1")}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"userId":1,"candidateIds":[1],"topK":0}""")
                }
            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        }

    @Test
    fun `topK 101 returns 422`() =
        rankingTest(warmProfile(), emptyMap()) { client ->
            val response =
                client.post("/ranking/score") {
                    header(HttpHeaders.Authorization, "Bearer ${token("1")}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"userId":1,"candidateIds":[1],"topK":101}""")
                }
            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        }

    @Test
    fun `null clusterService returns 200`() =
        rankingTest(warmProfile(), (1L..3L).associate { id -> id to basisVec((id - 1).toInt()) }) { client ->
            val response =
                client.post("/ranking/score") {
                    header(HttpHeaders.Authorization, "Bearer ${token("1")}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"userId":1,"candidateIds":[1,2,3],"topK":2}""")
                }
            assertEquals(HttpStatusCode.OK, response.status)
        }

    private fun rankingTest(
        profile: UserProfile,
        vectors: Map<Long, FloatArray>,
        clusterService: com.example.domain.cluster.ClusterService? = null,
        activeVersion: EmbeddingVersion = profile.embeddingVersion,
        block: suspend io.ktor.server.testing.ApplicationTestBuilder.(io.ktor.client.HttpClient) -> Unit,
    ) = testApplication {
        val handler =
            RankingHandler(
                profileAdapter(profile),
                embeddingAdapter(vectors),
                clusterService,
                activeEmbeddingVersion = { activeVersion },
            )
        application {
            configure(ReadinessGate().also { it.markReady() }, secret)
            configureRankingRoutes(handler)
        }
        block(client)
    }
}
