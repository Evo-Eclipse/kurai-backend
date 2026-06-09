package com.example.application.acquisition

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
import com.example.infrastructure.sqlite.Items
import com.example.infrastructure.sqlite.initSchema
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AcquisitionServiceTest {
    private lateinit var db: Database
    private lateinit var luceneDir: java.nio.file.Path
    private lateinit var vectorIndex: LuceneAdapter
    private val blobs = mutableMapOf<String, ByteArray>()

    private val fakeObjectStore =
        object : ObjectStorePort {
            override suspend fun put(
                key: String,
                bytes: ByteArray,
            ) {
                blobs[key] = bytes
            }

            override suspend fun get(key: String): GetResult =
                blobs[key]?.let { GetResult.Found(it) }
                    ?: GetResult.NotFound
        }

    private fun fakeInference(): InferenceService =
        InferenceService(
            preprocess = { FloatArray(3 * 224 * 224) },
            infer = { Scoring.l2Normalize(FloatArray(768) { (it + 1).toFloat() }) },
        )

    private fun fakeSource(images: List<RawImage>): ContentSource =
        object : ContentSource {
            override val platform = Platform("test")

            override suspend fun search(query: SourceQuery): List<com.example.domain.content.ContentItem> =
                images.take(query.limit).map {
                    com.example.domain.content.ContentItem(
                        it.platform,
                        it.sourceId,
                        it.originPostUrl,
                        it.cdnUrl,
                        it.rating,
                    )
                }

            override suspend fun fetch(
                query: SourceQuery,
                onImage: suspend (RawImage) -> Unit,
            ) {
                images.take(query.limit).forEach { onImage(it) }
            }
        }

    private fun makeImage(seed: Int): RawImage {
        val bytes = ByteArray(4) { (it + seed).toByte() }
        val md5 =
            java.security.MessageDigest
                .getInstance("MD5")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }
        return RawImage(
            platform = Platform("test"),
            sourceId = "item-$seed",
            originPostUrl = "https://example.com/$seed",
            cdnUrl = "https://cdn.example.com/$seed.jpg",
            rating = null,
            md5 = md5,
            bytes = bytes,
        )
    }

    private fun makeService(): AcquisitionService =
        AcquisitionService(
            jobRepository = AcquisitionJobRepository(db),
            inferenceService = fakeInference(),
            itemRepository = ItemRepository(db),
            vectorIndex = vectorIndex,
            objectStore = fakeObjectStore,
            activeEmbeddingVersion = { "v1" },
        )

    @BeforeTest
    fun setUp() {
        db =
            Database.connect(
                "jdbc:h2:mem:${System.nanoTime()};MODE=MySQL;DB_CLOSE_DELAY=-1",
                "org.h2.Driver",
            )
        initSchema(db)
        luceneDir = createTempDirectory("kurai-acq-test-")
        vectorIndex = LuceneAdapter(luceneDir)
    }

    @AfterTest
    fun tearDown() {
        vectorIndex.close()
        luceneDir.toFile().walkBottomUp().forEach { it.delete() }
        blobs.clear()
    }

    @Test
    fun `run writes all images to SQLite, Lucene, and ObjectStore`() {
        runBlocking {
            val images = (1..10).map { makeImage(it) }
            val service = makeService()
            service.createJob("job-1", "test", emptyList())
            service.run("job-1", "test", emptyList(), 10, fakeSource(images))

            val itemCount = transaction(db) { Items.selectAll().count() }
            assertEquals(10L, itemCount)
            assertEquals(10, blobs.size)
            assertEquals("done", service.getJob("job-1")!!.status)
        }
    }

    @Test
    fun `run is idempotent for same image bytes`() {
        runBlocking {
            val images = (1..10).map { makeImage(it) }
            val service = makeService()
            service.createJob("job-1", "test", emptyList())
            service.run("job-1", "test", emptyList(), 10, fakeSource(images))
            service.createJob("job-2", "test", emptyList())
            service.run("job-2", "test", emptyList(), 10, fakeSource(images))

            val itemCount = transaction(db) { Items.selectAll().count() }
            assertEquals(10L, itemCount, "Re-run with same images must not insert duplicates")
        }
    }

    @Test
    fun `activeEmbeddingVersion is stamped on every inserted item`() {
        runBlocking {
            val images = listOf(makeImage(1), makeImage(2))
            val service = makeService()
            service.createJob("job-1", "test", emptyList())
            service.run("job-1", "test", emptyList(), 2, fakeSource(images))

            val versions =
                transaction(db) {
                    Items.selectAll().map { it[Items.embeddingVersion] }
                }
            assertEquals(listOf("v1", "v1"), versions)
        }
    }

    @Test
    fun `job status transitions pending to running to done`() {
        runBlocking {
            val service = makeService()
            service.createJob("job-1", "test", emptyList())
            assertEquals("pending", service.getJob("job-1")!!.status)

            service.run("job-1", "test", emptyList(), 0, fakeSource(emptyList()))
            assertEquals("done", service.getJob("job-1")!!.status)
            assertNotNull(service.getJob("job-1")!!.completedAt)
        }
    }

    @Test
    fun `run marks job failed when inference throws`() {
        runBlocking {
            var callCount = 0
            val failingService =
                AcquisitionService(
                    jobRepository = AcquisitionJobRepository(db),
                    inferenceService =
                        InferenceService(
                            preprocess = { FloatArray(3 * 224 * 224) },
                            infer = {
                                if (++callCount == 3) throw RuntimeException("embed failed")
                                Scoring.l2Normalize(FloatArray(768) { (it + 1).toFloat() })
                            },
                        ),
                    itemRepository = ItemRepository(db),
                    vectorIndex = vectorIndex,
                    objectStore = fakeObjectStore,
                    activeEmbeddingVersion = { "v1" },
                )
            val images = (1..5).map { makeImage(it) }
            failingService.createJob("job-1", "test", emptyList())
            try {
                failingService.run("job-1", "test", emptyList(), 5, fakeSource(images))
            } catch (_: RuntimeException) {
            }
            val job = failingService.getJob("job-1")!!
            assertEquals("failed", job.status)
            assertEquals("embed failed", job.errorMessage)
        }
    }

    @Test
    fun `objectStore skips write for images already in SQLite`() {
        runBlocking {
            val images = (1..5).map { makeImage(it) }
            val service = makeService()
            service.createJob("job-1", "test", emptyList())
            service.run("job-1", "test", emptyList(), 5, fakeSource(images))
            val sizeAfterFirst = blobs.size

            service.createJob("job-2", "test", emptyList())
            service.run("job-2", "test", emptyList(), 5, fakeSource(images))

            assertEquals(sizeAfterFirst, blobs.size, "objectStore.put must not be called for already-stored images")
        }
    }

    @Test
    fun `objectStore write count tracks only new images across partial overlap`() {
        runBlocking {
            val first = (1..5).map { makeImage(it) }
            val overlap = (3..8).map { makeImage(it) }
            val service = makeService()

            service.createJob("job-1", "test", emptyList())
            service.run("job-1", "test", emptyList(), 5, fakeSource(first))
            val sizeAfterFirst = blobs.size
            assertEquals(5, sizeAfterFirst)

            service.createJob("job-2", "test", emptyList())
            service.run("job-2", "test", emptyList(), 6, fakeSource(overlap))

            // Images 3,4,5 already stored; 6,7,8 are new → 3 new writes
            assertTrue(blobs.size == 8, "Expected 8 unique blobs, got ${blobs.size}")
        }
    }
}
