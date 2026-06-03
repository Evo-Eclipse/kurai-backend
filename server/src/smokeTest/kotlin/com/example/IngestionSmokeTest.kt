package com.example

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.example.application.embedding.CachingEmbeddingAdapter
import com.example.application.profile.CachingProfileAdapter
import com.example.application.profile.EventBatcher
import com.example.domain.embedding.EmbedLookupPort
import com.example.domain.events.EventQueue
import com.example.domain.model.EmbeddingVersion
import com.example.domain.model.Prototype
import com.example.domain.model.UserProfile
import com.example.domain.profile.PendingUserEvent
import com.example.domain.profile.Scoring
import com.example.infrastructure.lucene.LuceneAdapter
import com.example.infrastructure.sqlite.EventRepository
import com.example.infrastructure.sqlite.ProfileRepository
import com.example.infrastructure.sqlite.initSchema
import com.example.ingestion.IngestionHandler
import com.example.ingestion.configureIngestionRoutes
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
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class IngestionSmokeTest {
    private lateinit var db: Database
    private lateinit var luceneDir: java.nio.file.Path
    private lateinit var lucene: LuceneAdapter

    private val secret = "ingestion-smoke-secret-32-bytes!!"
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
        luceneDir = createTempDirectory("kurai-ingestion-smoke-")
        lucene = LuceneAdapter(luceneDir)
        lucene.write(ITEM_ID, Scoring.l2Normalize(FloatArray(768) { (it + 1).toFloat() }))
        lucene.refresh()
    }

    @AfterTest
    fun tearDown() {
        lucene.close()
        luceneDir.toFile().walkBottomUp().forEach { it.delete() }
    }

    private fun buildHandler(): IngestionHandler {
        val profileRepo = ProfileRepository(db)
        val eventRepo = EventRepository(db)
        val embedLookup: EmbedLookupPort = { ids ->
            ids.mapNotNull { id -> lucene.getVector(id)?.let { id to it } }.toMap()
        }
        val cachingEmbedding = CachingEmbeddingAdapter(lookupFromStore = embedLookup)
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
            )
        val eventBatcher = EventBatcher(flush = { events -> eventRepo.appendBatch(events) })
        val eventQueue: EventQueue =
            EventQueue { event ->
                eventBatcher.enqueue(
                    PendingUserEvent(event.userId, event.itemId, event.sourceTag, event.embeddingVersion.value),
                )
            }
        return IngestionHandler(
            cachingProfile = cachingProfile,
            cachingEmbedding = cachingEmbedding,
            eventQueue = eventQueue,
            activeEmbeddingVersion = { EmbeddingVersion("v1") },
            resolveWeight = { 0.8f },
        )
    }

    private fun setup(block: suspend ApplicationTestBuilder.() -> Unit) =
        testApplication {
            application {
                configure(ReadinessGate().also { it.markReady() }, secret)
                configureIngestionRoutes(buildHandler())
            }
            block()
        }

    @Test
    fun `no JWT returns 401`() =
        setup {
            assertEquals(HttpStatusCode.Unauthorized, client.post("/ingestion/events").status)
        }

    @Test
    fun `JWT sub mismatch returns 403`() =
        setup {
            val response =
                client.post("/ingestion/events") {
                    header(HttpHeaders.Authorization, "Bearer ${token("99")}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"userId":1,"itemId":$ITEM_ID,"sourceTag":"like"}""")
                }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    @Test
    fun `valid JWT and pre-indexed item returns 204`() =
        setup {
            val response =
                client.post("/ingestion/events") {
                    header(HttpHeaders.Authorization, "Bearer ${token("1")}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"userId":1,"itemId":$ITEM_ID,"sourceTag":"like"}""")
                }
            assertEquals(HttpStatusCode.NoContent, response.status)
        }

    @Test
    fun `unknown itemId returns 422`() =
        setup {
            val response =
                client.post("/ingestion/events") {
                    header(HttpHeaders.Authorization, "Bearer ${token("1")}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"userId":1,"itemId":9999,"sourceTag":"like"}""")
                }
            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        }

    companion object {
        private const val ITEM_ID = 42L
    }
}
