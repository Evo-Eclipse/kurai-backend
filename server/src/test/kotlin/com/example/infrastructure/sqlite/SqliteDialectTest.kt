package com.example.infrastructure.sqlite

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.nio.file.Files
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SqliteDialectTest {
    private lateinit var db: Database

    @BeforeTest
    fun setUp() {
        // Use a temp file rather than :memory: so each new JDBC connection
        // sees the same schema (`:memory:` opens a fresh DB per connection).
        val tempDb =
            Files
                .createTempFile("kurai-sqlite-test-", ".db")
        tempDb.toFile().deleteOnExit()
        db = Database.connect("jdbc:sqlite:${tempDb.toAbsolutePath()}", "org.sqlite.JDBC")
        initSchema(db)
    }

    @Test
    fun `UPDATE on user_events is rejected by trigger`() {
        EventRepository(db).appendBatch(listOf(EventData(1L, 1L, "like", "v1")))
        assertFailsWith<Exception> {
            transaction(db) {
                exec("UPDATE user_events SET event_type = 'dislike' WHERE user_id = 1")
            }
        }
    }

    @Test
    fun `DELETE on user_events is rejected by trigger`() {
        EventRepository(db).appendBatch(listOf(EventData(1L, 1L, "like", "v1")))
        assertFailsWith<Exception> {
            transaction(db) {
                exec("DELETE FROM user_events WHERE user_id = 1")
            }
        }
    }

    @Test
    fun `partial index idx_user_events_positive exists`() {
        val name =
            transaction(db) {
                exec(
                    "SELECT name FROM sqlite_master WHERE type='index' AND name='idx_user_events_positive'",
                ) { rs -> if (rs.next()) rs.getString("name") else null }
            }
        assertEquals("idx_user_events_positive", name)
    }
}
