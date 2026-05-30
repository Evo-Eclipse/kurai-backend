package com.example.workers

import com.example.application.profile.CachingProfileAdapter
import com.example.domain.model.EmbeddingVersion
import com.example.domain.model.Prototype
import com.example.domain.model.UserEvent
import com.example.domain.model.UserProfile
import com.example.infrastructure.sqlite.ProfileRepository
import com.example.infrastructure.sqlite.initSchema
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.jetbrains.exposed.v1.jdbc.Database
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class ProfilePersistWorkerTest {
    private lateinit var db: Database
    private lateinit var profileRepo: ProfileRepository

    @BeforeTest
    fun setUp() {
        db =
            Database.connect(
                "jdbc:h2:mem:${System.nanoTime()};MODE=MySQL;DB_CLOSE_DELAY=-1",
                "org.h2.Driver",
            )
        initSchema(db)
        profileRepo = ProfileRepository(db)
    }

    private fun adapter(capacity: Long = 100) =
        CachingProfileAdapter(
            loadProfile = { null },
            loadEvents = { _, _ -> emptyList() },
            cacheCapacity = capacity,
        )

    @Test
    fun `flush persists dirty profiles after interval elapses`() =
        runTest {
            val cache = adapter()
            cache.update(
                userId = 1L,
                event =
                    UserEvent(
                        id = 1L,
                        userId = 1L,
                        itemId = 10L,
                        weight = 1.0f,
                        embeddingVersion = EmbeddingVersion("v1"),
                        ts = 0L,
                    ),
                itemVector = FloatArray(Prototype.VECTOR_DIM) { if (it == 0) 1f else 0f },
            )
            val worker = ProfilePersistWorker(cache, profileRepo, intervalMs = 30_000)
            val job = launch { worker.run() }

            advanceTimeBy(31_000)

            val row = profileRepo.load(1L)
            assertNotNull(row, "Profile should be persisted after 30 s interval")
            assertEquals(1L, row.userId)

            job.cancel()
            job.join()
        }

    @Test
    fun `second flush is a no-op when cache has no new dirty profiles`() =
        runTest {
            val cache = adapter()
            cache.update(
                userId = 1L,
                event =
                    UserEvent(
                        id = 1L,
                        userId = 1L,
                        itemId = 10L,
                        weight = 1.0f,
                        embeddingVersion = EmbeddingVersion("v1"),
                        ts = 0L,
                    ),
                itemVector = FloatArray(Prototype.VECTOR_DIM) { if (it == 0) 1f else 0f },
            )
            val worker = ProfilePersistWorker(cache, profileRepo, intervalMs = 30_000)

            worker.flush()
            assertNotNull(profileRepo.load(1L), "first flush should persist")

            worker.flush()
            // The snapshot was already drained; a second flush is a no-op. Verify no crash.
            assertNotNull(profileRepo.load(1L))
        }

    @Test
    fun `flush in finally block persists on cancellation`() =
        runTest {
            val cache = adapter()
            cache.update(
                userId = 2L,
                event =
                    UserEvent(
                        id = 1L,
                        userId = 2L,
                        itemId = 10L,
                        weight = 1.0f,
                        embeddingVersion = EmbeddingVersion("v1"),
                        ts = 0L,
                    ),
                itemVector = FloatArray(Prototype.VECTOR_DIM) { if (it == 0) 1f else 0f },
            )
            val worker = ProfilePersistWorker(cache, profileRepo, intervalMs = 60_000)
            val job = launch { worker.run() }

            // Let the worker start and enter the try block (reach the first delay).
            // Without this yield, the job is cancelled before it ever starts and
            // the finally-block flush is never called.
            yield()
            job.cancel()
            job.join()

            // Finally block should have flushed remaining dirty profiles
            assertNotNull(profileRepo.load(2L), "Dirty profile must be persisted in finally block")
        }

    @Test
    fun `no upsert when cache has no dirty profiles`() =
        runTest {
            val cache = adapter()
            val worker = ProfilePersistWorker(cache, profileRepo, intervalMs = 30_000)
            val job = launch { worker.run() }

            advanceTimeBy(31_000)

            assertNull(profileRepo.load(999L), "No rows should appear for unseen userId")

            job.cancel()
            job.join()
        }

    @Test
    fun `lastAppliedEventId matches the profile state after flush`() =
        runTest {
            val cache = adapter()
            val userId = 5L
            val vec = FloatArray(Prototype.VECTOR_DIM) { if (it == 0) 1f else 0f }
            // Simulate 3 events arriving with increasing ids
            repeat(3) { i ->
                cache.update(
                    userId = userId,
                    event =
                        UserEvent(
                            id = (i + 1).toLong(),
                            userId = userId,
                            itemId = i.toLong(),
                            weight = 1.0f,
                            embeddingVersion = EmbeddingVersion("v1"),
                            ts = 0L,
                        ),
                    itemVector = vec,
                )
            }
            val worker = ProfilePersistWorker(cache, profileRepo, intervalMs = 30_000)
            val job = launch { worker.run() }
            advanceTimeBy(31_000)

            val row = profileRepo.load(userId)
            assertNotNull(row)
            // The profile in the cache has lastAppliedEventId = 3 (last event)
            assertEquals(3L, row.lastAppliedEventId)

            job.cancel()
            job.join()
        }
}
