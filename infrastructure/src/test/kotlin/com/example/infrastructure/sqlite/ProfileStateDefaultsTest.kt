package com.example.infrastructure.sqlite
import com.example.domain.profile.PendingUserEvent
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The A/B columns land with sensible defaults now so the future experiment
 * engine needs no schema migration — only new writers.
 */
class ProfileStateDefaultsTest {
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
    fun `a freshly upserted profile defaults to the control cohort`() =
        runBlocking {
            ProfileRepository(db).upsert(userId = 1L, embeddingVersion = "v1", lastAppliedEventId = 0L)

            val row =
                transaction(db) {
                    UserProfileState.selectAll().where { UserProfileState.userId eq 1L }.single()
                }
            assertEquals("v1", row[UserProfileState.assignedEmbeddingVersion])
            assertEquals(Cohort.CONTROL, row[UserProfileState.cohort])
            assertEquals(5, row[UserProfileState.prototypesTarget])
        }

    @Test
    fun `an appended event snapshots the control cohort by default`() =
        runBlocking {
            EventRepository(db).appendBatch(
                listOf(PendingUserEvent(userId = 1L, itemId = 10L, sourceTag = "like", embeddingVersion = "v1")),
            )

            val cohort =
                transaction(db) {
                    UserEvents.selectAll().where { UserEvents.userId eq 1L }.single()[UserEvents.cohort]
                }
            assertEquals(Cohort.CONTROL, cohort)
        }
}
