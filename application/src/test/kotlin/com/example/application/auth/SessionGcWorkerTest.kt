package com.example.application.auth

import com.example.domain.auth.AuthSessionPort
import com.example.infrastructure.sqlite.AuthSessionRepository
import com.example.infrastructure.sqlite.UserRepository
import com.example.infrastructure.sqlite.initSchema
import org.jetbrains.exposed.v1.jdbc.Database
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SessionGcWorkerTest {
    private lateinit var db: Database
    private lateinit var sessions: AuthSessionPort
    private var userId: Long = 0

    private val now = 1_000_000_000L
    private val retentionMs = 10_000L

    @BeforeTest
    fun setUp() {
        db =
            Database.connect(
                "jdbc:h2:mem:${System.nanoTime()};MODE=MySQL;DB_CLOSE_DELAY=-1",
                "org.h2.Driver",
            )
        initSchema(db)
        sessions = AuthSessionRepository(db)
        userId = UserRepository(db).insertAnonymous(now)
    }

    private fun session(
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

    @Test
    fun `purge is a no-op when nothing is old enough`() {
        session("active", expiresAt = now + 60_000)
        val worker = SessionGcWorker(sessions, intervalMs = { 1 }, retentionMs = { retentionMs }, clock = { now })

        assertEquals(0, worker.purgeOnce())
        assertNotNull(sessions.findById("active"))
    }
}
