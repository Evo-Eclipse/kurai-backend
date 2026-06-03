package com.example.infrastructure.sqlite
import com.example.domain.profile.PendingUserEvent
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
        val (id1, isNew1) =
            repo.insertIdempotent(
                md5 = "948fa4499b4ba297484b540c7c263273",
                url = "https://cdn.example.com/1.jpg",
                origin = "https://example.com/posts/1",
                rating = null,
                embeddingVersion = "v1",
                indexedAt = 1_700_000_000L,
            )
        val (id2, isNew2) =
            repo.insertIdempotent(
                md5 = "948fa4499b4ba297484b540c7c263273",
                url = "https://cdn.example.com/1.jpg",
                origin = "https://example.com/posts/1",
                rating = null,
                embeddingVersion = "v1",
                indexedAt = 1_700_000_000L,
            )
        assertEquals(id1, id2)
        assert(isNew1) { "first insert should be new" }
        assert(!isNew2) { "second insert should not be new" }
    }

    @Test
    fun `ItemRepository insertIdempotent assigns distinct ids for different md5`() {
        val repo = ItemRepository(db)
        val (id1) =
            repo.insertIdempotent(
                md5 = "948fa4499b4ba297484b540c7c263273",
                url = "https://cdn.example.com/a.jpg",
                origin = "https://example.com/posts/1",
                rating = "s",
                embeddingVersion = "v1",
                indexedAt = 1_700_000_000L,
            )
        val (id2) =
            repo.insertIdempotent(
                md5 = "85eaa59d6ed4d2db104a478cc2f160b6",
                url = "https://cdn.example.com/b.jpg",
                origin = "https://example.com/posts/2",
                rating = null,
                embeddingVersion = "v1",
                indexedAt = 1_700_000_001L,
            )
        assert(id1 != id2)
    }

    @Test
    fun `EventRepository batch-insert 10K events in one transaction`() {
        val repo = EventRepository(db)
        val events =
            (1..10_000).map { i ->
                PendingUserEvent(userId = 1L, itemId = i.toLong(), sourceTag = "like", embeddingVersion = "v1")
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
        repo.insert(id = "job-1", status = "pending", origin = "https://example.com/posts.json", query = "wave")
        val job = repo.findById("job-1")
        assertNotNull(job)
        assertEquals("job-1", job.id)
        assertEquals("pending", job.status)
        assertEquals("https://example.com/posts.json", job.origin)
        assertEquals("wave", job.query)
        assertNull(job.userId)
        assertNull(job.completedAt)
        assertNull(job.errorMessage)
    }

    @Test
    fun `AcquisitionJobRepository updateStatus stores errorMessage`() {
        val repo = AcquisitionJobRepository(db)
        repo.insert(id = "job-err", status = "running", origin = "src", query = "q")
        repo.updateStatus("job-err", "failed", errorMessage = "something went wrong")
        val job = repo.findById("job-err")
        assertNotNull(job)
        assertEquals("failed", job.status)
        assertEquals("something went wrong", job.errorMessage)
    }

    @Test
    fun `AcquisitionJobRepository insert with userId`() {
        val repo = AcquisitionJobRepository(db)
        repo.insert(
            id = "job-2",
            status = "running",
            origin = "https://example.com/posts.json",
            query = "mountains",
            userId = 99L,
        )
        val job = repo.findById("job-2")
        assertNotNull(job)
        assertEquals(99L, job.userId)
    }

    @Test
    fun `AcquisitionJobRepository findById returns null for unknown id`() {
        assertNull(AcquisitionJobRepository(db).findById("nonexistent"))
    }
}
