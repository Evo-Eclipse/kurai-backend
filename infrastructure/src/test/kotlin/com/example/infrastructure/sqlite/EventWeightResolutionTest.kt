package com.example.infrastructure.sqlite
import com.example.domain.events.EventWeightPort
import com.example.domain.profile.PendingUserEvent
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class EventWeightResolutionTest {
    private lateinit var db: Database
    private lateinit var events: EventRepository
    private lateinit var weights: EventWeightRepository

    @BeforeTest
    fun setUp() {
        db =
            Database.connect(
                "jdbc:h2:mem:${System.nanoTime()};MODE=MySQL;DB_CLOSE_DELAY=-1",
                "org.h2.Driver",
            )
        initSchema(db)
        events = EventRepository(db)
        weights = EventWeightRepository(db)
    }

    @Test
    fun `unknown tag resolves to the neutral default`() =
        runBlocking {
            events.appendBatch(
                listOf(PendingUserEvent(userId = 1L, itemId = 10L, sourceTag = "mystery", embeddingVersion = "v1")),
            )

            val resolved = events.loadSince(userId = 1L, sinceEventId = 0L).single()
            assertEquals(EventWeightPort.DEFAULT_EVENT_WEIGHT.toFloat(), resolved.weight)
        }

    @Test
    fun `weight is resolved live, so a backfill changes the next read`() =
        runBlocking {
            events.appendBatch(
                listOf(PendingUserEvent(userId = 1L, itemId = 10L, sourceTag = "like", embeddingVersion = "v1")),
            )

            // Before the operator defines "like", it is neutral.
            assertEquals(0f, events.loadSince(1L, 0L).single().weight)

            // Backfilling the dictionary takes effect on the next read — no event rewrite.
            weights.upsert("like", 0.75, now = 1L)
            assertEquals(0.75f, events.loadSince(1L, 0L).single().weight)
        }

    @Test
    fun `loadPositiveSince keeps only events whose resolved weight is positive`() =
        runBlocking {
            weights.upsert("like", 1.0, now = 0L)
            weights.upsert("dislike", -1.0, now = 0L)
            events.appendBatch(
                listOf(
                    PendingUserEvent(1L, 10L, "like", "v1"),
                    PendingUserEvent(1L, 11L, "dislike", "v1"),
                    PendingUserEvent(1L, 12L, "mystery", "v1"), // resolves to 0 -> not positive
                ),
            )

            val positiveItemIds = events.loadPositiveSince(1L, 0L).map { it.itemId }
            assertEquals(listOf(10L), positiveItemIds)
        }
}
