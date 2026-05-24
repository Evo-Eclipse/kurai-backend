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
        source: String,
        sourceId: String,
        embeddingVersion: String,
        indexedAt: String,
    ): Long =
        transaction(db) {
            Items.insertIgnore {
                it[Items.md5] = md5
                it[Items.sourceTag] = source
                it[Items.sourceId] = sourceId
                it[Items.embeddingVersion] = embeddingVersion
                it[Items.indexedAt] = indexedAt
            }
            Items.selectAll().where { Items.md5 eq md5 }.single()[Items.id]
        }
}
