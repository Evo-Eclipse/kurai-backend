package com.example.infrastructure.sqlite
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SystemStateRepositoryTest {
    private lateinit var db: Database
    private lateinit var repo: SystemStateRepository

    @BeforeTest
    fun setUp() {
        db =
            Database.connect(
                "jdbc:h2:mem:${System.nanoTime()};MODE=MySQL;DB_CLOSE_DELAY=-1",
                "org.h2.Driver",
            )
        initSchema(db)
        repo = SystemStateRepository(db)
    }

    private fun insertEmbeddingGeneration(
        version: String,
        status: String,
    ) = transaction(db) {
        EmbeddingGenerations.insert {
            it[EmbeddingGenerations.version] = version
            it[EmbeddingGenerations.status] = status
            it[onnxSha256] = "sha-$version"
        }
    }

    private fun statusOf(version: String): String =
        transaction(db) {
            EmbeddingGenerations
                .selectAll()
                .where { EmbeddingGenerations.version eq version }
                .single()[EmbeddingGenerations.status]
        }

    private fun insertIndexGeneration(
        embeddingVersion: String,
        status: String,
    ): Long =
        transaction(db) {
            IndexGenerations.insert {
                it[IndexGenerations.embeddingVersion] = embeddingVersion
                it[IndexGenerations.status] = status
                it[indexPath] = "idx/$embeddingVersion"
            }[IndexGenerations.id]
        }

    private fun indexStatusOf(id: Long): String =
        transaction(db) {
            IndexGenerations
                .selectAll()
                .where { IndexGenerations.id eq id }
                .single()[IndexGenerations.status]
        }

    @Test
    fun `seed is idempotent and read returns empty defaults`() =
        runBlocking {
            repo.seedIfMissing(now = 1L)
            repo.seedIfMissing(now = 2L) // no-op

            val state = repo.read()
            assertNull(state.defaultEmbeddingVersion)
            assertNull(state.activeIndexId)
            assertNull(state.activeClusterId)
            assertEquals(0L, state.totalItems)
            assertEquals(0L, state.embeddedItems)

            // Exactly one row.
            val count = transaction(db) { SystemState.selectAll().count() }
            assertEquals(1L, count)
        }

    @Test
    fun `setDefaultEmbeddingVersion flips status and repoints atomically`() =
        runBlocking {
            repo.seedIfMissing(now = 0L)
            insertEmbeddingGeneration("v1", GenerationStatus.ACTIVE)
            insertEmbeddingGeneration("v2", GenerationStatus.BUILDING)

            repo.setDefaultEmbeddingVersion("v2", now = 100L)

            assertEquals("v2", repo.read().defaultEmbeddingVersion)
            assertEquals(GenerationStatus.ACTIVE, statusOf("v2"))
            assertEquals(GenerationStatus.DEPRECATED, statusOf("v1"))
        }

    @Test
    fun `activateIndex flips status and repoints atomically`() =
        runBlocking {
            repo.seedIfMissing(now = 0L)
            val i1 = insertIndexGeneration("idx/v1", GenerationStatus.BUILDING)
            val i2 = insertIndexGeneration("idx/v2", GenerationStatus.BUILDING)

            repo.activateIndex(i1, now = 10L)
            assertEquals(i1, repo.read().activeIndexId)

            repo.activateIndex(i2, now = 20L)
            assertEquals(i2, repo.read().activeIndexId)
            assertEquals(GenerationStatus.ACTIVE, indexStatusOf(i2))
            assertEquals(GenerationStatus.DEPRECATED, indexStatusOf(i1))
        }

    @Test
    fun `activateCluster flips status and repoints atomically`() =
        runBlocking {
            repo.seedIfMissing(now = 0L)
            val clusters = ClusterGenerationRepository(db)
            val c1 =
                clusters.createBuilding(
                    "v1",
                    clusterCount = 23,
                    catalogSizeAtBuild = 100,
                    centroidsPath = "c1.bin",
                )
            val c2 =
                clusters.createBuilding(
                    "v1",
                    clusterCount = 23,
                    catalogSizeAtBuild = 110,
                    centroidsPath = "c2.bin",
                )

            repo.activateCluster(c1, now = 10L)
            assertEquals(c1, repo.read().activeClusterId)
            assertEquals(GenerationStatus.ACTIVE, clusters.findById(c1)?.status)

            repo.activateCluster(c2, now = 20L)
            assertEquals(c2, repo.read().activeClusterId)
            assertEquals(GenerationStatus.ACTIVE, clusters.findById(c2)?.status)
            assertEquals(GenerationStatus.DEPRECATED, clusters.findById(c1)?.status)
        }

    @Test
    fun `setCounts updates catalog counters`() =
        runBlocking {
            repo.seedIfMissing(now = 0L)
            repo.setCounts(totalItems = 1_000L, embeddedItems = 980L, now = 5L)

            val state = repo.read()
            assertEquals(1_000L, state.totalItems)
            assertEquals(980L, state.embeddedItems)
            assertEquals(5L, state.updatedAt)
        }

    @Test
    fun `read after fresh seed has no active pointers`() =
        runBlocking {
            repo.seedIfMissing(now = 0L)
            assertFalse(repo.read().defaultEmbeddingVersion != null)
            assertTrue(repo.read().activeIndexId == null)
        }
}
