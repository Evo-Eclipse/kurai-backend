package com.example.infrastructure.sqlite

import org.jetbrains.exposed.v1.jdbc.Database
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RepositoryCrudTest {
    private lateinit var db: Database

    @BeforeTest
    fun setUp() {
        // Unique DB name per test run to avoid state leakage between test classes.
        db =
            Database.connect(
                "jdbc:h2:mem:${System.nanoTime()};MODE=MySQL;DB_CLOSE_DELAY=-1",
                "org.h2.Driver",
            )
        initSchema(db)
    }

    @Test
    fun `ItemRepository insertIdempotent returns same id on re-insert`() {
        val repo = ItemRepository(db)
        val id1 = repo.insertIdempotent("abc123", "kurai", "12345", "v1", "2025-01-01T00:00:00Z")
        val id2 = repo.insertIdempotent("abc123", "kurai", "12345", "v1", "2025-01-01T00:00:00Z")
        assertEquals(id1, id2)
    }

    @Test
    fun `ItemRepository insertIdempotent assigns distinct ids for different md5`() {
        val repo = ItemRepository(db)
        val id1 = repo.insertIdempotent("aaa", "kurai", "1", "v1", "2025-01-01T00:00:00Z")
        val id2 = repo.insertIdempotent("bbb", "kurai", "2", "v1", "2025-01-01T00:00:00Z")
        assert(id1 != id2)
    }

    @Test
    fun `EventRepository batch-insert 10K events in one transaction`() {
        val repo = EventRepository(db)
        val events =
            (1..10_000).map { i ->
                EventData(userId = 1L, itemId = i.toLong(), eventType = "view", embeddingVersion = "v1")
            }
        val ids = repo.appendBatch(events)
        assertEquals(10_000, ids.size)
    }

    @Test
    fun `ProfileRepository upsert and load round-trip`() {
        val repo = ProfileRepository(db)
        repo.upsert(userId = 42L, embeddingVersion = "v1", lastAppliedEventId = 100L)
        val loaded = repo.load(42L)
        assertNotNull(loaded)
        assertEquals(42L, loaded.userId)
        assertEquals("v1", loaded.embeddingVersion)
        assertEquals(100L, loaded.lastAppliedEventId)
    }

    @Test
    fun `ProfileRepository upsert advances lastAppliedEventId`() {
        val repo = ProfileRepository(db)
        repo.upsert(userId = 7L, embeddingVersion = "v1", lastAppliedEventId = 10L)
        repo.upsert(userId = 7L, embeddingVersion = "v1", lastAppliedEventId = 55L)
        assertEquals(55L, repo.load(7L)!!.lastAppliedEventId)
    }

    @Test
    fun `ProfileRepository load returns null for unknown user`() {
        assertNull(ProfileRepository(db).load(999L))
    }

    @Test
    fun `AcquisitionJobRepository insert and findById round-trip`() {
        val repo = AcquisitionJobRepository(db)
        repo.insert(id = "job-1", status = "pending", source = "kurai")
        val job = repo.findById("job-1")
        assertNotNull(job)
        assertEquals("job-1", job.id)
        assertEquals("pending", job.status)
        assertEquals("kurai", job.source)
    }

    @Test
    fun `AcquisitionJobRepository findById returns null for unknown id`() {
        assertNull(AcquisitionJobRepository(db).findById("nonexistent"))
    }
}
