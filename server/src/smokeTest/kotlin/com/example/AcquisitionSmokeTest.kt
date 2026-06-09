package com.example

import com.example.acquisition.AcquisitionHandler
import com.example.acquisition.configureAcquisitionRoutes
import com.example.application.acquisition.AcquisitionService
import com.example.domain.content.ContentSource
import com.example.domain.content.Platform
import com.example.domain.content.RawImage
import com.example.domain.content.SourceQuery
import com.example.domain.inference.InferenceService
import com.example.domain.profile.Scoring
import com.example.domain.storage.GetResult
import com.example.domain.storage.ObjectStorePort
import com.example.infrastructure.lucene.LuceneAdapter
import com.example.infrastructure.sqlite.AcquisitionJobRepository
import com.example.infrastructure.sqlite.ItemRepository
import com.example.infrastructure.sqlite.initSchema
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AcquisitionSmokeTest {
    private lateinit var db: Database
    private lateinit var luceneDir: java.nio.file.Path
    private lateinit var vectorIndex: LuceneAdapter

    private val fakeObjectStore =
        object : ObjectStorePort {
            override suspend fun put(
                key: String,
                bytes: ByteArray,
            ) {}

            override suspend fun get(key: String): GetResult = GetResult.NotFound
        }

    private val fakeSource =
        object : ContentSource {
            override val platform = Platform("test")

            override suspend fun search(query: SourceQuery): List<com.example.domain.content.ContentItem> = emptyList()

            override suspend fun fetch(
                query: SourceQuery,
                onImage: suspend (RawImage) -> Unit,
            ) {
                // no images
            }
        }

    private fun fakeInference(): InferenceService =
        InferenceService(
            preprocess = { FloatArray(3 * 224 * 224) },
            infer = { Scoring.l2Normalize(FloatArray(768) { (it + 1).toFloat() }) },
        )

    @BeforeTest
    fun setUp() {
        db =
            Database.connect(
                "jdbc:h2:mem:${System.nanoTime()};MODE=MySQL;DB_CLOSE_DELAY=-1",
                "org.h2.Driver",
            )
        initSchema(db)
        luceneDir = createTempDirectory("kurai-smoke-")
        vectorIndex = LuceneAdapter(luceneDir)
    }

    @AfterTest
    fun tearDown() {
        vectorIndex.close()
        luceneDir.toFile().walkBottomUp().forEach { it.delete() }
    }

    private fun makeHandler(sources: Map<String, ContentSource> = mapOf("test" to fakeSource)): AcquisitionHandler {
        val service =
            AcquisitionService(
                jobRepository = AcquisitionJobRepository(db),
                inferenceService = fakeInference(),
                itemRepository = ItemRepository(db),
                vectorIndex = vectorIndex,
                objectStore = fakeObjectStore,
                activeEmbeddingVersion = { "v1" },
                contentSources = sources,
            )
        return AcquisitionHandler(service, CoroutineScope(SupervisorJob()))
    }

    @Test
    fun `POST acquisition run unknown source returns 400`() =
        testApplication {
            application {
                configure(ReadinessGate().also { it.markReady() })
                configureAcquisitionRoutes(makeHandler(emptyMap()))
            }
            val response =
                client.post("/acquisition/run") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"source":"unsplash","tags":[],"limit":10}""")
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `GET acquisition jobs nonexistent id returns 404`() =
        testApplication {
            application {
                configure(ReadinessGate().also { it.markReady() })
                configureAcquisitionRoutes(makeHandler())
            }
            val response = client.get("/acquisition/jobs/no-such-id")
            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `POST acquisition run valid source returns 202 with jobId`() =
        testApplication {
            application {
                configure(ReadinessGate().also { it.markReady() })
                configureAcquisitionRoutes(makeHandler())
            }
            val response =
                client.post("/acquisition/run") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"source":"test","tags":[],"limit":0}""")
                }
            assertEquals(HttpStatusCode.Accepted, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertNotNull(body["jobId"]?.jsonPrimitive?.content)
        }

    @Test
    fun `GET acquisition jobs returns 200 with status and errorMessage fields`() =
        testApplication {
            application {
                configure(ReadinessGate().also { it.markReady() })
                configureAcquisitionRoutes(makeHandler())
            }
            val postResponse =
                client.post("/acquisition/run") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"source":"test","tags":[],"limit":0}""")
                }
            assertEquals(HttpStatusCode.Accepted, postResponse.status)
            val jobId =
                Json
                    .parseToJsonElement(postResponse.bodyAsText())
                    .jsonObject["jobId"]!!
                    .jsonPrimitive.content

            val getResponse = client.get("/acquisition/jobs/$jobId")
            assertEquals(HttpStatusCode.OK, getResponse.status)
            val body = Json.parseToJsonElement(getResponse.bodyAsText()).jsonObject
            assertNotNull(body["status"])
            // errorMessage is always present in the response (null for non-failed jobs)
            assert(body.containsKey("errorMessage"))
        }

    @Test
    fun `POST acquisition run with limit above max returns 400`() =
        testApplication {
            application {
                configure(ReadinessGate().also { it.markReady() })
                configureAcquisitionRoutes(makeHandler())
            }
            val response =
                client.post("/acquisition/run") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"source":"test","tags":[],"limit":99999}""")
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `POST acquisition run with negative limit returns 400`() =
        testApplication {
            application {
                configure(ReadinessGate().also { it.markReady() })
                configureAcquisitionRoutes(makeHandler())
            }
            val response =
                client.post("/acquisition/run") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"source":"test","tags":[],"limit":-1}""")
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
}
