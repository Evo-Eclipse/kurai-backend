package com.example.infrastructure.sqlite

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class ItemRepository(
    private val db: Database,
) {
    fun insertIdempotent(
        md5: String,
        url: String,
        origin: String,
        rating: String?,
        embeddingVersion: String,
        indexedAt: Long,
    ): Pair<Long, Boolean> =
        transaction(db) {
            val stmt =
                Items.insertIgnore {
                    it[Items.md5] = md5
                    it[Items.url] = url
                    it[Items.origin] = origin
                    it[Items.rating] = rating
                    it[Items.embeddingVersion] = embeddingVersion
                    it[Items.indexedAt] = indexedAt
                }
            if (stmt.insertedCount > 0) {
                Pair(stmt[Items.id], true)
            } else {
                Pair(Items.selectAll().where { Items.md5 eq md5 }.single()[Items.id], false)
            }
        }
}
