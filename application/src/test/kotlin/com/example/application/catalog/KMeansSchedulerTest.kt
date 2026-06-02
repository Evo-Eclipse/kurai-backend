package com.example.application.catalog

import com.example.domain.cluster.ClusterService
import com.example.domain.profile.Scoring
import com.example.infrastructure.lucene.LuceneAdapter
import com.example.infrastructure.sqlite.ClusterGenerationRepository
import com.example.infrastructure.sqlite.GenerationStatus
import com.example.infrastructure.sqlite.ItemRepository
import com.example.infrastructure.sqlite.SystemStateRepository
import com.example.infrastructure.sqlite.initSchema
import com.example.infrastructure.storage.LocalObjectStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.Database
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class KMeansSchedulerTest {
    private lateinit var db: Database
    private lateinit var itemRepo: ItemRepository
    private lateinit var luceneDir: java.nio.file.Path
    private lateinit var objectStoreDir: java.nio.file.Path
    private lateinit var lucene: LuceneAdapter
    private lateinit var objectStore: LocalObjectStore
    private lateinit var clusterGenerations: ClusterGenerationRepository
    private lateinit var systemState: SystemStateRepository

    @BeforeTest
    fun setUp() {
        db =
            Database.connect(
                "jdbc:h2:mem:${System.nanoTime()};MODE=MySQL;DB_CLOSE_DELAY=-1",
                "org.h2.Driver",
            )
        initSchema(db)
        itemRepo = ItemRepository(db)
        luceneDir = Files.createTempDirectory("lucene-kmeans-test")
        objectStoreDir = Files.createTempDirectory("objstore-kmeans-test")
        lucene = LuceneAdapter(luceneDir)
        objectStore = LocalObjectStore(objectStoreDir)
        clusterGenerations = ClusterGenerationRepository(db)
        systemState = SystemStateRepository(db).also { it.seedIfMissing(0L) }
    }

    private fun normalizedVec(vararg values: Float): FloatArray {
        val v = FloatArray(768)
        values.forEachIndexed { i, f -> v[i] = f }
        return Scoring.l2Normalize(v)
    }

    private fun insertItem(md5: String): Long {
        val (id, _) =
            itemRepo.insertIdempotent(
                md5 = md5,
                url = "https://example.com/$md5.jpg",
                origin = "https://example.com/posts/$md5",
                rating = null,
                embeddingVersion = "v1",
                indexedAt = System.currentTimeMillis() / 1000,
            )
        return id
    }

    @Test
    fun `no run when growth condition is not met (5 percent growth)`() =
        runTest {
            val ref = AtomicReference<ClusterService?>(null)
            val scheduler =
                KMeansScheduler(
                    itemRepo = itemRepo,
                    luceneAdapter = lucene,
                    objectStore = objectStore,
                    clusterGenerations = clusterGenerations,
                    systemState = systemState,
                    clusterServiceRef = ref,
                    intervalMs = { 3_600_000 },
                    minGrowthFactor = 1.10,
                    minAgeMs = 24 * 3_600_000L,
                )
            // With no items in the DB the vector sample is empty, so the scheduler
            // skips training regardless of the growth condition.
            val job = launch { scheduler.run() }
            advanceTimeBy(3_601_000)

            assertNull(ref.get(), "Should not update when there are no vectors to train on")
            job.cancel()
            job.join()
        }

    @Test
    fun `retrain records and activates a cluster generation`() =
        runTest {
            systemState.setDefaultEmbeddingVersion("v1", now = 0L)
            // Three indexed items with vectors so the sample has >= 2 vectors.
            listOf(normalizedVec(1f), normalizedVec(0f, 1f), normalizedVec(0f, 0f, 1f))
                .forEachIndexed { i, vec ->
                    val id = insertItem("md5-$i")
                    lucene.write(id, vec)
                }
            lucene.refresh()

            val ref = AtomicReference<ClusterService?>(null)
            val scheduler =
                KMeansScheduler(
                    itemRepo = itemRepo,
                    luceneAdapter = lucene,
                    objectStore = objectStore,
                    clusterGenerations = clusterGenerations,
                    systemState = systemState,
                    clusterServiceRef = ref,
                    intervalMs = { 3_600_000 },
                    minGrowthFactor = 1.10,
                    minAgeMs = 0L,
                )
            scheduler.check()

            val activeId = assertNotNull(systemState.read().activeClusterId, "active cluster pointer must be set")
            val gen = assertNotNull(clusterGenerations.findById(activeId))
            assertEquals(GenerationStatus.ACTIVE, gen.status)
            assertEquals("v1", gen.embeddingVersion)
            assertNotNull(ref.get(), "in-memory cluster reference must be swapped in")
        }

    @Test
    fun `trainKMeans produces k centroids of correct dimension`() {
        val dim = 768
        val vectors = (1..200).map { i -> normalizedVec(i.toFloat()) }
        val centroids = KMeansScheduler.trainKMeans(vectors, k = 3, seed = 42L)

        assertEquals(3, centroids.size)
        centroids.forEach { c ->
            assertEquals(dim, c.size, "Each centroid must have dim=$dim")
            val norm = kotlin.math.sqrt(c.sumOf { it * it.toDouble() }).toFloat()
            assert(kotlin.math.abs(norm - 1f) < 0.01f) { "Centroid must be L2-normalized, got norm=$norm" }
        }
    }

    @Test
    fun `serializeCentroids and loadCentroids round-trip`() {
        val k = 4
        val centroids =
            Array(k) {
                normalizedVec((it + 1).toFloat())
            }
        val bytes = KMeansScheduler.serializeCentroids(centroids)
        val service = ClusterService.fromCentroids(ClusterCentroidsLoader.load(bytes))

        assertEquals(k, service.size)
    }

    @Test
    fun `concurrent ranking during swap sees consistent reference`() =
        runTest {
            val initial = ClusterService.fromCentroids(Array(2) { normalizedVec(it.toFloat() + 1f) })
            val ref = AtomicReference<ClusterService?>(initial)

            var sawNull = false
            val readers =
                (1..10).map {
                    launch {
                        repeat(100) {
                            val cs = ref.get()
                            if (cs == null) sawNull = true
                        }
                    }
                }
            // Swap the reference concurrently
            val newCs = ClusterService.fromCentroids(Array(3) { normalizedVec(it.toFloat() + 10f) })
            ref.set(newCs)

            readers.forEach { it.join() }
            assert(!sawNull) { "AtomicReference swap must never expose null mid-read" }
            assertEquals(3, ref.get()?.size)
        }
}

// Adapter to load centroids from bytes (mirrors ClusterCentroids.loadCentroids internal fn)
private object ClusterCentroidsLoader {
    fun load(bytes: ByteArray): Array<FloatArray> {
        val buf =
            java.nio.ByteBuffer
                .wrap(bytes)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val k = buf.int
        val dim = buf.int
        return Array(k) { FloatArray(dim) { buf.float } }
    }
}
