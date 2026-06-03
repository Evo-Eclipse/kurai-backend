package com.example

import com.example.application.profile.CachingProfileAdapter
import com.example.application.profile.ProfilePersistWorker
import com.example.domain.model.EmbeddingVersion
import com.example.domain.model.UserEvent
import com.example.domain.profile.PendingUserEvent
import com.example.domain.profile.Scoring
import com.example.infrastructure.sqlite.EventRepository
import com.example.infrastructure.sqlite.EventWeightRepository
import com.example.infrastructure.sqlite.ProfileRepository
import com.example.infrastructure.sqlite.initSchema
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.jetbrains.exposed.v1.jdbc.Database
import kotlin.math.sqrt
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LifecycleTest {
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
        val v = FloatArray(768) { (it + seed + 1).toFloat() }
        return Scoring.l2Normalize(v)
    }

    @Test
    fun `startup recovery replays events not reflected in persisted profile`() =
        runTest {
            val userId = 1L
            // Persist a profile with lastAppliedEventId = 0 (simulates kill -9 before flush)
            profileRepo.upsert(userId = userId, embeddingVersion = "v1", lastAppliedEventId = 0L)
            // Write 5 events to user_events (simulates EventBatcher flush that did happen)
            val vec = normalizedVec(1)
            val events =
                (1..5).map { i ->
                    PendingUserEvent(userId = userId, itemId = i.toLong(), sourceTag = "like", embeddingVersion = "v1")
                }
            eventRepo.appendBatch(events)

            val cachingProfile =
                CachingProfileAdapter(
                    loadProfile = { uid ->
                        profileRepo.load(uid)?.let { row ->
                            com.example.domain.model.UserProfile(
                                userId = row.userId,
                                embeddingVersion = EmbeddingVersion(row.embeddingVersion),
                                positivePrototypes = emptyList(),
                                negativePrototypes = emptyList(),
                                sessionVector = FloatArray(768),
                                longTermVector = FloatArray(768),
                                lastAppliedEventId = row.lastAppliedEventId,
                            )
                        }
                    },
                    loadEvents = { uid, sinceId ->
                        eventRepo.loadSince(uid, sinceId).map { row ->
                            UserEvent(
                                id = row.itemId,
                                userId = row.userId,
                                itemId = row.itemId,
                                weight = row.weight,
                                embeddingVersion = EmbeddingVersion(row.embeddingVersion),
                                ts = 0L,
                            ) to vec
                        }
                    },
                )

            // Simulate startup recovery: load all known users
            profileRepo.loadAllUserIds().forEach { uid -> cachingProfile.getOrLoad(uid) }

            // Profile should have replayed all 5 events — sessionVector is non-zero
            val profile = cachingProfile.getOrLoad(userId)
            val norm = sqrt(profile.sessionVector.sumOf { it * it.toDouble() }).toFloat()
            assertTrue(norm > 0.01f, "sessionVector should be non-zero after event replay, norm=$norm")
        }

    @Test
    fun `empty DB starts clean with no errors`() =
        runTest {
            val userIds = profileRepo.loadAllUserIds()
            assertEquals(0, userIds.size, "expected no users in empty DB")
        }

    @Test
    fun `markStopping sets gate to not ready`() {
        val gate = ReadinessGate()
        gate.markReady()
        assertTrue(gate.isReady())
        gate.markStopping()
        assertFalse(gate.isReady())
    }

    @Test
    fun `workers flush dirty profiles on scope cancellation`() =
        runTest {
            val userId = 2L
            profileRepo.upsert(userId = userId, embeddingVersion = "v1", lastAppliedEventId = 0L)

            val cachingProfile =
                CachingProfileAdapter(
                    loadProfile = { uid ->
                        profileRepo.load(uid)?.let { row ->
                            com.example.domain.model.UserProfile(
                                userId = row.userId,
                                embeddingVersion = EmbeddingVersion(row.embeddingVersion),
                                positivePrototypes = emptyList(),
                                negativePrototypes = emptyList(),
                                sessionVector = FloatArray(768),
                                longTermVector = FloatArray(768),
                                lastAppliedEventId = row.lastAppliedEventId,
                            )
                        }
                    },
                    loadEvents = { _, _ -> emptyList() },
                )

            // Simulate an in-memory event that hasn't been persisted yet
            val vec = normalizedVec(42)
            cachingProfile.update(
                userId = userId,
                event =
                    UserEvent(
                        id = 99L,
                        userId = userId,
                        itemId = 7L,
                        weight = 1.0f,
                        embeddingVersion = EmbeddingVersion("v1"),
                        ts = 0L,
                    ),
                itemVector = vec,
            )

            val scope = CoroutineScope(SupervisorJob())
            val worker = ProfilePersistWorker(cachingProfile, profileRepo, intervalMs = { 30_000 })
            scope.launch { worker.run() }
            yield() // let coroutine enter try block before cancel
            scope.cancel()
            (scope.coroutineContext[Job] ?: error("no Job")).join()

            // The finally block should have flushed the dirty profile
            val persisted = profileRepo.load(userId)
            assertEquals(99L, persisted?.lastAppliedEventId, "finally block should persist lastAppliedEventId=99")
        }
}
