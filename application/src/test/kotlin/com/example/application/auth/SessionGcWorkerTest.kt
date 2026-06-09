package com.example.application.auth

import com.example.domain.auth.AuthSessionPort
import com.example.infrastructure.sqlite.AuthSessionRepository
import com.example.infrastructure.sqlite.UserRepository
import com.example.infrastructure.sqlite.initSchema
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionGcWorkerTest {
    private lateinit var db: Database
    private lateinit var sessions: AuthSessionPort
    private var userId: Long = 0

    private val now = 1_000_000_000L
    private val retentionMs = 10_000L

    @BeforeTest
    fun setUp() {
        runBlocking {
            db =
                Database.connect(
                    "jdbc:h2:mem:${System.nanoTime()};MODE=MySQL;DB_CLOSE_DELAY=-1",
                    "org.h2.Driver",
                )
            initSchema(db)
            sessions = AuthSessionRepository(db)
            userId = UserRepository(db).insertAnonymous(now)
        }
    }

    private suspend fun session(
        id: String,
        expiresAt: Long,
    ) = sessions.insert(
        id = id,
        userId = userId,
        deviceLabel = null,
        refreshHash = "hash-$id",
        expiresAt = expiresAt,
        now = now,
    )

    @Test
    fun `purges only sessions expired beyond the retention window`() {
        runBlocking {
            // cutoff = now - retentionMs.
            session("old", expiresAt = now - retentionMs - 1) // expired beyond grace -> purged
            session("recent", expiresAt = now - retentionMs + 1) // expired but within grace -> kept
            session("active", expiresAt = now + 60_000) // still valid -> kept

            val worker = SessionGcWorker(sessions, intervalMs = { 1 }, retentionMs = { retentionMs }, clock = { now })
            val removed = worker.purgeOnce()

            assertEquals(1, removed)
            assertNull(sessions.findById("old"), "expired-beyond-grace session should be purged")
            assertNotNull(sessions.findById("recent"), "session within grace must survive")
            assertNotNull(sessions.findById("active"), "active session must survive")
        }
    }

    @Test
    fun `run reports the purged count to onPurge`() {
        runBlocking {
            session("old", expiresAt = now - retentionMs - 1)
            val reported = AtomicInteger(-1)
            val job =
                launch {
                    SessionGcWorker(
                        sessions,
                        intervalMs = { 5 },
                        retentionMs = { retentionMs },
                        clock = { now },
                        onPurge = { reported.set(it) },
                    ).run()
                }
            while (reported.get() < 0) delay(5)
            job.cancelAndJoin()
            assertEquals(1, reported.get())
        }
    }

    @Test
    fun `survives a failed sweep and keeps running`() {
        runBlocking {
            session("old", expiresAt = now - retentionMs - 1)
            val calls = AtomicInteger(0)
            val job =
                launch {
                    SessionGcWorker(
                        sessions,
                        intervalMs = { 5 },
                        retentionMs = { retentionMs },
                        clock = { now },
                        // Throw on the first tick; the worker must log and keep looping.
                        onPurge = { if (calls.incrementAndGet() == 1) error("boom on first sweep") },
                    ).run()
                }
            while (calls.get() < 2) delay(5)
            job.cancelAndJoin()
            assertTrue(calls.get() >= 2, "worker should have run a second sweep after the first failed")
        }
    }

    @Test
    fun `purge is a no-op when nothing is old enough`() {
        runBlocking {
            session("active", expiresAt = now + 60_000)
            val worker = SessionGcWorker(sessions, intervalMs = { 1 }, retentionMs = { retentionMs }, clock = { now })

            assertEquals(0, worker.purgeOnce())
            assertNotNull(sessions.findById("active"))
        }
    }
}
