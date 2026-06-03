package com.example.infrastructure.sqlite

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

/** SQLite write serialization: one in-process writer matches WAL single-writer. */
@OptIn(ExperimentalCoroutinesApi::class)
val sqliteDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

/**
 * Runs [block] on [sqliteDispatcher] via Exposed's [suspendTransaction].
 * Use from all SQLite repository ports so request threads never block on JDBC.
 */
suspend fun <T> sqliteTransaction(
    db: Database,
    block: suspend JdbcTransaction.() -> T,
): T =
    withContext(sqliteDispatcher) {
        suspendTransaction(db) {
            block()
        }
    }
