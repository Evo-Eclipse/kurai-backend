package com.example

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.example.application.acquisition.AcquisitionService
import com.example.application.content.MetadataService
import com.example.application.profile.CachingProfileAdapter
import com.example.content.ContentHandler
import com.example.content.configureContentRoutes
import com.example.domain.content.ContentSource
import com.example.domain.content.Platform
import com.example.domain.content.RawImage
import com.example.domain.content.SourceQuery
import com.example.domain.inference.InferenceService
import com.example.domain.model.EmbeddingVersion
import com.example.domain.model.Prototype
import com.example.domain.model.UserProfile
import com.example.domain.profile.Scoring
import com.example.domain.storage.GetResult
import com.example.domain.storage.ObjectStorePort
import com.example.infrastructure.lucene.LuceneAdapter
import com.example.infrastructure.sqlite.AcquisitionJobRepository
import com.example.infrastructure.sqlite.ItemRepository
import com.example.infrastructure.sqlite.initSchema
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
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
import org.jetbrains.exposed.v1.jdbc.Database
import java.security.MessageDigest
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class ContentSmokeTest {
    private lateinit var db: Database
    private lateinit var luceneDir: java.nio.file.Path
    private lateinit var vectorIndex: LuceneAdapter

    /** Owns the proxy write-behind jobs; joined before the index is closed. */
    private val persistScope = CoroutineScope(SupervisorJob())

    private val secret = "content-smoke-secret-32-bytes-long!!"
    private val algo = Algorithm.HMAC256(secret)

    private fun token(sub: String): String =
        JWT
            .create()
            .withClaim("sub", sub)
            .withExpiresAt(Date(System.currentTimeMillis() + 60_000))
            .sign(algo)

    /** Records every put so tests can assert the write-behind archive. */
    private val storedBlobs = ConcurrentHashMap<String, ByteArray>()

    private val recordingObjectStore =
        object : ObjectStorePort {
            override suspend fun put(
                key: String,
                bytes: ByteArray,
            ) {
                storedBlobs[key] = bytes
            }

            override suspend fun get(key: String): GetResult =
                storedBlobs[key]?.let { GetResult.Found(it) } ?: GetResult.NotFound
        }

    /** Emits a fixed number of synthetic images; tags/limit are ignored. */
    private fun fakeSearchSource(count: Int): ContentSource =
        object : ContentSource {
            override val platform = Platform("test")

            override suspend fun fetch(
                query: SourceQuery,
                onImage: suspend (RawImage) -> Unit,
            ) {
                repeat(count) { i -> onImage(rawImage("img-$i", byteArrayOf(i.toByte()))) }
            }
        }

    /** Treats each tag as a URL and "downloads" deterministic bytes for it. */
    private val fakeCdnSource =
        object : ContentSource {
            override val platform = Platform("cdn")

            override suspend fun fetch(
                query: SourceQuery,
                onImage: suspend (RawImage) -> Unit,
            ) {
                query.tags.take(query.limit).forEach { url -> onImage(rawImage(url, url.toByteArray())) }
            }
        }

    private fun rawImage(
        ref: String,
        bytes: ByteArray,
    ): RawImage =
        RawImage(
            platform = Platform("test"),
            sourceId = ref,
            originPostUrl = ref,
            cdnUrl = ref,
            rating = null,
            md5 = md5Hex(bytes),
            bytes = bytes,
        )

    private fun md5Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun fakeInference(): InferenceService =
        InferenceService(
            preprocess = { FloatArray(3 * 224 * 224) },
            infer = { Scoring.l2Normalize(FloatArray(Prototype.VECTOR_DIM) { 1f }) },
        )

    private fun profileAdapter(version: String): CachingProfileAdapter =
        CachingProfileAdapter(
            loadProfile = { userId ->
                UserProfile(
                    userId = userId,
                    embeddingVersion = EmbeddingVersion(version),
                    positivePrototypes = emptyList(),
                    negativePrototypes = emptyList(),
                    sessionVector = FloatArray(Prototype.VECTOR_DIM),
                    longTermVector = FloatArray(Prototype.VECTOR_DIM),
                    lastAppliedEventId = 0L,
                )
            },
            loadEvents = { _, _ -> emptyList() },
        )

    private fun makeHandler(
        profileVersion: String = "v1",
        activeVersion: String = "v1",
        proxyImageCount: Int = 2,
    ): ContentHandler {
        val acquisitionService =
            AcquisitionService(
                jobRepository = AcquisitionJobRepository(db),
                inferenceService = fakeInference(),
                itemRepository = ItemRepository(db),
                vectorIndex = vectorIndex,
                objectStore = recordingObjectStore,
                activeEmbeddingVersion = { activeVersion },
                contentSources = emptyMap(),
            )
        val metadataService =
            MetadataService(
                inferenceService = fakeInference(),
                cachingProfile = profileAdapter(profileVersion),
                activeEmbeddingVersion = { EmbeddingVersion(activeVersion) },
            )
        return ContentHandler(
            metadataService = metadataService,
            acquisitionService = acquisitionService,
            contentSources = mapOf("test" to fakeSearchSource(proxyImageCount), "cdn" to fakeCdnSource),
            persistScope = persistScope,
            activeEmbeddingVersion = { activeVersion },
        )
    }

    @BeforeTest
    fun setUp() {
        db =
            Database.connect(
                "jdbc:h2:mem:${System.nanoTime()};MODE=MySQL;DB_CLOSE_DELAY=-1",
                "org.h2.Driver",
            )
        initSchema(db)
        luceneDir = createTempDirectory("kurai-content-smoke-")
        vectorIndex = LuceneAdapter(luceneDir)
    }

    @AfterTest
    fun tearDown() {
        // Let any write-behind persist jobs finish before closing the index,
        // otherwise a lagging vectorIndex.write hits an already-closed writer.
        runBlocking { checkNotNull(persistScope.coroutineContext[Job]).children.toList().forEach { it.join() } }
        vectorIndex.close()
        luceneDir.toFile().walkBottomUp().forEach { it.delete() }
    }

    private fun setup(
        handler: ContentHandler = makeHandler(),
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
        application {
            configure(ReadinessGate().also { it.markReady() }, secret)
            configureContentRoutes(handler)
        }
        block()
    }

    private suspend fun awaitCatalogCount(min: Long) {
        val repo = ItemRepository(db)
        repeat(MAX_POLLS) {
            if (repo.countAll() >= min) return
            delay(POLL_INTERVAL_MS)
        }
        fail("catalog never reached $min items")
    }

    private suspend fun awaitStoredCount(min: Int) {
        repeat(MAX_POLLS) {
            if (storedBlobs.size >= min) return
            delay(POLL_INTERVAL_MS)
        }
        fail("object store never reached $min blobs")
    }

    private fun itemCount(json: String): Int =
        Json
            .parseToJsonElement(json)
            .jsonObject["items"]!!
            .jsonArray.size

    @Test
    fun `proxy without JWT returns 401`() =
        setup {
            val response =
                client.post("/content/proxy") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"source":"test","tags":["cat"],"limit":2}""")
                }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `shuttle without JWT returns 401`() =
        setup {
            val response =
                client.post("/content/shuttle") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"urls":["http://x/1"]}""")
                }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `proxy returns 200 with metadata and persists images write-behind`() =
        setup {
            val response =
                client.post("/content/proxy") {
                    header(HttpHeaders.Authorization, "Bearer ${token("1")}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"source":"test","tags":["cat"],"limit":2}""")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(2, itemCount(response.bodyAsText()))
            awaitCatalogCount(2)
        }

    @Test
    fun `shuttle with JSON urls returns 200 with metadata`() =
        setup {
            val response =
                client.post("/content/shuttle") {
                    header(HttpHeaders.Authorization, "Bearer ${token("1")}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"urls":["http://cdn/a","http://cdn/b","http://cdn/c"]}""")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(3, itemCount(response.bodyAsText()))
            // Write-behind archive to Space, keyed by the readable uuid name.
            awaitStoredCount(3)
            assertTrue(storedBlobs.keys.all { OBJECT_KEY_REGEX.matches(it) }, storedBlobs.keys.toString())
        }

    @Test
    fun `shuttle with multipart upload returns 200 with metadata`() =
        setup {
            val response =
                client.post("/content/shuttle") {
                    header(HttpHeaders.Authorization, "Bearer ${token("1")}")
                    setBody(
                        MultiPartFormDataContent(
                            formData {
                                append(
                                    "file",
                                    byteArrayOf(1, 2, 3, 4),
                                    Headers.build {
                                        append(HttpHeaders.ContentDisposition, "filename=\"a.png\"")
                                    },
                                )
                            },
                        ),
                    )
                }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(1, itemCount(response.bodyAsText()))
            awaitStoredCount(1)
        }

    @Test
    fun `shuttle returns 503 when the caller profile is on a stale version`() =
        setup(makeHandler(profileVersion = "v1", activeVersion = "v2")) {
            val response =
                client.post("/content/shuttle") {
                    header(HttpHeaders.Authorization, "Bearer ${token("1")}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"urls":["http://cdn/a"]}""")
                }
            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertTrue(response.headers[HttpHeaders.RetryAfter] != null)
        }

    companion object {
        private const val MAX_POLLS = 100
        private const val POLL_INTERVAL_MS = 20L
        private val OBJECT_KEY_REGEX =
            Regex("images/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.[a-z]+")
    }
}
