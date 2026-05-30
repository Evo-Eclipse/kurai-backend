package com.example.infrastructure.sqlite

import org.jetbrains.exposed.v1.core.vendors.SQLiteDialect
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.Connection

private val ALL_TABLES =
    arrayOf(
        Items,
        UserEvents,
        UserProfileState,
        UserPrototypes,
        EmbeddingGenerations,
        IndexGenerations,
        AcquisitionJobs,
    )

fun initSchema(db: Database) {
    if (db.dialect is SQLiteDialect) {
        applySqlitePragmas(db)
    }
    transaction(db) {
        SchemaUtils.create(*ALL_TABLES)
        if (db.dialect is SQLiteDialect) {
            applySqliteTriggersAndIndices()
        }
    }
}

/**
 * SQLite PRAGMAs `journal_mode` and `synchronous` change connection-level state
 * and must be issued outside an active transaction. We grab the raw JDBC
 * connection from Exposed and toggle auto-commit so the PRAGMAs apply cleanly.
 */
private fun applySqlitePragmas(db: Database) {
    transaction(db) {
        val raw = connection.connection as Connection
        val previous = raw.autoCommit
        raw.autoCommit = true
        try {
            raw.createStatement().use { stmt ->
                stmt.execute("PRAGMA journal_mode=WAL")
                stmt.execute("PRAGMA synchronous=NORMAL")
            }
        } finally {
            raw.autoCommit = previous
        }
    }
}

private fun JdbcTransaction.applySqliteTriggersAndIndices() {
    exec(
        """
        CREATE TRIGGER IF NOT EXISTS prevent_user_events_update
        BEFORE UPDATE ON user_events
        BEGIN
            SELECT RAISE(ABORT, 'user_events is append-only');
        END
        """.trimIndent(),
    )
    exec(
        """
        CREATE TRIGGER IF NOT EXISTS prevent_user_events_delete
        BEFORE DELETE ON user_events
        BEGIN
            SELECT RAISE(ABORT, 'user_events is append-only');
        END
        """.trimIndent(),
    )
    // Covers migration replay (ORDER BY id) and proto-split positive-event scans.
    exec(
        """
        CREATE INDEX IF NOT EXISTS idx_user_events_positive
        ON user_events(user_id, id)
        WHERE weight > 0
        """.trimIndent(),
    )
}
