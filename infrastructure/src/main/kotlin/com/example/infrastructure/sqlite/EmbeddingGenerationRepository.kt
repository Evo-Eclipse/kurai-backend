package com.example.infrastructure.sqlite

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class EmbeddingGenerationRepository(
    private val db: Database,
) {
    fun getActiveVersion(): String? =
        transaction(db) {
            EmbeddingGenerations
                .selectAll()
                .where { EmbeddingGenerations.status eq "active" }
                .singleOrNull()
                ?.get(EmbeddingGenerations.version)
        }
}
