package com.example

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.example.acquisition.AcquisitionHandler
import com.example.acquisition.configureAcquisitionRoutes
import com.example.application.acquisition.AcquisitionService
import com.example.application.content.MetadataService
import com.example.application.embedding.CachingEmbeddingAdapter
import com.example.application.profile.CachingProfileAdapter
import com.example.application.profile.RankingService
import com.example.content.ContentHandler
import com.example.content.configureContentRoutes
import com.example.domain.content.ContentItem
import com.example.domain.content.ContentSource
import com.example.domain.content.Platform
import com.example.domain.content.RawImage
import com.example.domain.content.SourceQuery
import com.example.domain.content.md5Hex
import com.example.domain.events.EventQueue
import com.example.domain.inference.InferenceService
import com.example.domain.model.EmbeddingVersion
import com.example.domain.model.Prototype
import com.example.domain.profile.Scoring
import com.example.domain.storage.GetResult
import com.example.domain.storage.ObjectStorePort
import com.example.infrastructure.lucene.LuceneAdapter
import com.example.infrastructure.sqlite.AcquisitionJobRepository
import com.example.infrastructure.sqlite.ItemRepository
import com.example.infrastructure.sqlite.initSchema
import com.example.ingestion.IngestionHandler
import com.example.ingestion.configureIngestionRoutes
import com.example.profile.RankingHandler
import com.example.profile.configureRankingRoutes
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import java.util.Date
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * End-to-end over the **public HTTP API**: one app wiring the real services and
 * handlers (shared H2 + temp Lucene + fake inference + fake content sources)
 * via their public constructors, driving the whole product flow — acquisition →
 * ingestion → ranking → content — entirely through HTTP. Versions agree by
 * construction (`activeEmbeddingVersion` = cold-start `default`), so no internal
 * state is poked. True full-boot / real-externals E2E is the container's job.
 */
class EndToEndTest {
    private lateinit var db: Database
    private lateinit var luceneDir: java.nio.file.Path
    private lateinit var vectorIndex: LuceneAdapter

    /** Acquisition jobs + content write-behind; joined before the index closes. */
    private val scope = CoroutineScope(SupervisorJob())

    private val secret = "e2e-secret-key-at-least-32-bytes!!"
    private val algo = Algorithm.HMAC256(secret)
    private val version = "default"

    private fun token(sub: String): String =
        JWT
            .create()
            .withClaim("sub", sub)
            .withExpiresAt(Date(System.currentTimeMillis() + 60_000))
            .sign(algo)

    private val fakeObjectStore =
        object : ObjectStorePort {
            override suspend fun put(
                key: String,
                bytes: ByteArray,
            ) {}

            override suspend fun get(key: String): GetResult = GetResult.NotFound
        }

    private fun fakeInference(): InferenceService =
        InferenceService(
            preprocess = { FloatArray(3 * 224 * 224) },
            infer = { Scoring.l2Normalize(FloatArray(Prototype.VECTOR_DIM) { 1f }) },
        )

    /** Search-backed source: lists/emits [count] unique synthetic images. */
    private fun fakeSearchSource(count: Int): ContentSource =
        object : ContentSource {
            override val platform = Platform("test")

            override suspend fun search(query: SourceQuery): List<ContentItem> =
                (0 until minOf(count, query.limit)).map { i ->
                    ContentItem(platform, "img-$i", "https://post/$i", "https://cdn/img-$i", null)
                }

            override suspend fun fetch(
                query: SourceQuery,
                onImage: suspend (RawImage) -> Unit,
            ) {
                repeat(minOf(count, query.limit)) { i ->
                    val bytes = "img-$i".toByteArray()
                    onImage(
                        RawImage(
                            platform,
                            "img-$i",
                            "https://post/$i",
                            "https://cdn/img-$i",
                            null,
                            md5Hex(bytes),
                            bytes,
                        ),
                    )
                }
            }
        }

    /** Downloads caller URLs to deterministic bytes (backs /content/scores). */
    private fun fakeCdnSource(): ContentSource =
        object : ContentSource {
            override val platform = Platform("cdn")

            override suspend fun search(query: SourceQuery): List<ContentItem> =
                query.tags.take(query.limit).map { ContentItem(platform, it, it, it, null) }

            override suspend fun fetch(
                query: SourceQuery,
                onImage: suspend (RawImage) -> Unit,
            ) {
                query.tags.take(query.limit).forEach { url ->
                    val bytes = url.toByteArray()
                    onImage(RawImage(platform, url, url, url, null, md5Hex(bytes), bytes))
                }
            }
        }

    @BeforeTest
    fun setUp() {
        db =
            Database.connect(
                "jdbc:h2:mem:${System.nanoTime()};MODE=MySQL;DB_CLOSE_DELAY=-1",
                "org.h2.Driver",
            )
        initSchema(db)
        luceneDir = createTempDirectory("kurai-e2e-")
        vectorIndex = LuceneAdapter(luceneDir)
    }

    @AfterTest
    fun tearDown() {
        runBlocking { checkNotNull(scope.coroutineContext[Job]).children.toList().forEach { it.join() } }
        vectorIndex.close()
        luceneDir.toFile().walkBottomUp().forEach { it.delete() }
    }

    /** Wires the full graph from public constructors and mounts every route. */
    private fun ApplicationTestBuilder.installApp(sourceImages: Int) {
        val contentSources = mapOf("test" to fakeSearchSource(sourceImages), "cdn" to fakeCdnSource())
        val cachingEmbedding =
            CachingEmbeddingAdapter(
                lookupFromStore = { ids ->
                    ids.mapNotNull { id -> vectorIndex.getVector(id)?.let { id to it } }.toMap()
                },
            )
        val cachingProfile =
            CachingProfileAdapter(
                loadProfile = { null },
                loadEvents = { _, _ -> emptyList() },
            )
        val acquisitionService =
            AcquisitionService(
                jobRepository = AcquisitionJobRepository(db),
                inferenceService = fakeInference(),
                itemRepository = ItemRepository(db),
                vectorIndex = vectorIndex,
                objectStore = fakeObjectStore,
                activeEmbeddingVersion = { version },
                contentSources = contentSources,
            )
        val rankingService =
            RankingService(
                cachingProfile = cachingProfile,
                cachingEmbedding = cachingEmbedding,
                getClusterService = { null },
                activeEmbeddingVersion = { EmbeddingVersion(version) },
            )
        val metadataService =
            MetadataService(
                inferenceService = fakeInference(),
                cachingProfile = cachingProfile,
                activeEmbeddingVersion = { EmbeddingVersion(version) },
            )
        application {
            configure(ReadinessGate().also { it.markReady() }, secret)
            configureAcquisitionRoutes(AcquisitionHandler(acquisitionService, scope))
            configureIngestionRoutes(
                IngestionHandler(
                    cachingProfile = cachingProfile,
                    cachingEmbedding = cachingEmbedding,
                    eventQueue = EventQueue { },
                    activeEmbeddingVersion = { EmbeddingVersion(version) },
                    resolveWeight = { 1f },
                ),
            )
            configureRankingRoutes(RankingHandler(rankingService))
            configureContentRoutes(
                ContentHandler(
                    metadataService = metadataService,
                    acquisitionService = acquisitionService,
                    contentSources = contentSources,
                    persistScope = scope,
                    activeEmbeddingVersion = { version },
                ),
            )
        }
    }

    @Test
    fun `full flow - acquire, ingest, rank, content`() =
        testApplication {
            installApp(sourceImages = 100)

            // 1. Acquire 100 items through the real pipeline.
            val runResp =
                client.post("/acquisition/run") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"source":"test","tags":["x"],"limit":100}""")
                }
            assertEquals(HttpStatusCode.Accepted, runResp.status)
            val jobId =
                Json
                    .parseToJsonElement(runResp.bodyAsText())
                    .jsonObject["jobId"]!!
                    .jsonPrimitive.content
            awaitJobDone(jobId)

            // 2. Ingest: 5 users x 10 events each (items 1..100 are now indexed).
            for (userId in 1..5) {
                val jwt = token("$userId")
                repeat(10) { i ->
                    val resp =
                        client.post("/ingestion/events") {
                            header(HttpHeaders.Authorization, "Bearer $jwt")
                            contentType(ContentType.Application.Json)
                            setBody("""{"userId":$userId,"itemId":${(i % 100) + 1},"sourceTag":"favorite"}""")
                        }
                    assertEquals(HttpStatusCode.NoContent, resp.status, "ingest u=$userId i=$i")
                }
            }

            // 3. Rank: 5 users.
            for (userId in 1..5) {
                val resp =
                    client.post("/ranking/score") {
                        header(HttpHeaders.Authorization, "Bearer ${token("$userId")}")
                        contentType(ContentType.Application.Json)
                        setBody("""{"userId":$userId,"candidateIds":[1,2,3,4,5,6,7,8,9,10],"topK":5}""")
                    }
                assertEquals(HttpStatusCode.OK, resp.status, "rank u=$userId")
                assertTrue(itemCount(resp.bodyAsText()) in 1..5)
            }

            // 4. Content: proxy lists refs, scores enriches caller URLs.
            val proxy =
                client.post("/content/proxy") {
                    header(HttpHeaders.Authorization, "Bearer ${token("1")}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"source":"test","tags":["x"],"limit":5}""")
                }
            assertEquals(HttpStatusCode.OK, proxy.status)
            assertEquals(5, itemCount(proxy.bodyAsText()))

            val scores =
                client.post("/content/scores") {
                    header(HttpHeaders.Authorization, "Bearer ${token("1")}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"urls":["https://cdn/a","https://cdn/b"]}""")
                }
            assertEquals(HttpStatusCode.OK, scores.status)
            assertEquals(2, itemCount(scores.bodyAsText()))
        }

    private suspend fun ApplicationTestBuilder.awaitJobDone(jobId: String) {
        repeat(200) {
            val body = client.get("/acquisition/jobs/$jobId").bodyAsText()
            when (
                Json
                    .parseToJsonElement(body)
                    .jsonObject["status"]
                    ?.jsonPrimitive
                    ?.content
            ) {
                "done" -> return
                "failed" -> fail("acquisition job failed: $body")
            }
            delay(25)
        }
        fail("acquisition job did not finish in time")
    }

    private fun itemCount(json: String): Int =
        Json
            .parseToJsonElement(json)
            .jsonObject["items"]!!
            .jsonArray.size
}
