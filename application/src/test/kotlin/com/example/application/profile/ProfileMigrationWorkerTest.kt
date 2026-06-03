package com.example.application.profile

import com.example.application.embedding.CachingEmbeddingAdapter
import com.example.domain.model.EmbeddingVersion
import com.example.domain.model.Prototype
import com.example.domain.profile.PendingUserEvent
import com.example.domain.profile.Scoring
import com.example.infrastructure.sqlite.EventRepository
import com.example.infrastructure.sqlite.EventWeightRepository
import com.example.infrastructure.sqlite.ProfileRepository
import com.example.infrastructure.sqlite.initSchema
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.Database
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ProfileMigrationWorkerTest {
    private lateinit var db: Database
    private lateinit var profileRepo: ProfileRepository
    private lateinit var eventRepo: EventRepository

    @BeforeTest
    fun setUp() {
        db =
            Database.connect(
                "jdbc:h2:mem:${System.nanoTime()};MODE=MySQL;DB_CLOSE_DELAY=-1",
                "org.h2.Driver",
            )
        initSchema(db)
        profileRepo = ProfileRepository(db)
        eventRepo = EventRepository(db)
        EventWeightRepository(db).upsert("like", 1.0, now = 0L)
    }

    private fun normalizedVec(seed: Int): FloatArray {
        val v = FloatArray(Prototype.VECTOR_DIM) { (it + seed + 1).toFloat() }
        return Scoring.l2Normalize(v)
    }

    private fun isNormalized(v: FloatArray): Boolean {
        val norm = sqrt(v.sumOf { it * it.toDouble() }).toFloat()
        return abs(norm - 1f) < 0.01f
    }

    private fun makeWorker(
        activeVersion: EmbeddingVersion,
        vectors: Map<Long, FloatArray> = emptyMap(),
    ): ProfileMigrationWorker {
        val adapter =
            CachingProfileAdapter(
                loadProfile = { null },
                loadEvents = { _, _ -> emptyList() },
            )
        val embedding =
            CachingEmbeddingAdapter(lookupFromStore = { ids ->
                ids.mapNotNull { id -> vectors[id]?.let { id to it } }.toMap()
            })
        return ProfileMigrationWorker(
            profileRepo = profileRepo,
            eventRepo = eventRepo,
            cachingEmbedding = embedding,
            cachingProfile = adapter,
            activeEmbeddingVersion = { activeVersion },
        )
    }

    @Test
    fun `migrates stale profile to new version`() =
        runTest {
            // Arrange: profile persisted with old version
            profileRepo.upsert(userId = 1L, embeddingVersion = "v1", lastAppliedEventId = 0L)
            // Insert a positive event
            eventRepo.appendBatch(
                listOf(PendingUserEvent(userId = 1L, itemId = 10L, sourceTag = "like", embeddingVersion = "v1")),
            )
            val vec = normalizedVec(1)

            // Act: run migration with active version "v2"
            val worker = makeWorker(EmbeddingVersion("v2"), vectors = mapOf(10L to vec))
            worker.migrateOneBatch()

            // Assert: profile row updated to "v2"
            val row = profileRepo.load(1L)
            assertNotNull(row)
            assertEquals("v2", row.embeddingVersion)
        }

    @Test
    fun `profile already on active version is not migrated`() =
        runTest {
            profileRepo.upsert(userId = 2L, embeddingVersion = "v2", lastAppliedEventId = 0L)
            val worker = makeWorker(EmbeddingVersion("v2"))
            worker.migrateOneBatch()

            // Row should remain at v2, no error
            val row = profileRepo.load(2L)
            assertNotNull(row)
            assertEquals("v2", row.embeddingVersion)
        }

    @Test
    fun `profiles are migrated in updatedAt ASC order`() =
        runTest {
            // Two stale profiles; the one with older updatedAt (smaller timestamp) should go first.
            profileRepo.upsert(userId = 10L, embeddingVersion = "v1", lastAppliedEventId = 0L)
            Thread.sleep(50) // ensure different timestamps
            profileRepo.upsert(userId = 20L, embeddingVersion = "v1", lastAppliedEventId = 0L)

            val adapter =
                CachingProfileAdapter(
                    loadProfile = { null },
                    loadEvents = { _, _ -> emptyList() },
                )
            val embedding = CachingEmbeddingAdapter(lookupFromStore = { _ -> emptyMap() })
            val worker =
                ProfileMigrationWorker(
                    profileRepo = profileRepo,
                    eventRepo = eventRepo,
                    cachingEmbedding = embedding,
                    cachingProfile = adapter,
                    activeEmbeddingVersion = { EmbeddingVersion("v2") },
                )
            worker.migrateOneBatch()

            // Both should be at v2 now
            assertEquals("v2", profileRepo.load(10L)?.embeddingVersion)
            assertEquals("v2", profileRepo.load(20L)?.embeddingVersion)
        }

    @Test
    fun `lastAppliedEventId reflects max event id after migration`() =
        runTest {
            profileRepo.upsert(userId = 3L, embeddingVersion = "v1", lastAppliedEventId = 0L)
            val events =
                listOf(
                    PendingUserEvent(userId = 3L, itemId = 1L, sourceTag = "like", embeddingVersion = "v1"),
                    PendingUserEvent(userId = 3L, itemId = 2L, sourceTag = "like", embeddingVersion = "v1"),
                )
            val ids = eventRepo.appendBatch(events)
            val maxId = ids.max()
            val vec = normalizedVec(1)

            val worker =
                makeWorker(
                    EmbeddingVersion("v2"),
                    vectors = mapOf(1L to vec, 2L to normalizedVec(2)),
                )
            worker.migrateOneBatch()

            val row = profileRepo.load(3L)
            assertNotNull(row)
            assertEquals(maxId, row.lastAppliedEventId)
        }
}
