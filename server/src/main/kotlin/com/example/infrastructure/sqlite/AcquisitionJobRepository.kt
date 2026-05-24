package com.example.infrastructure.sqlite

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

data class AcquisitionJobRow(
    val id: String,
    val status: String,
    val source: String,
    val createdAt: String,
)

class AcquisitionJobRepository(
    private val db: Database,
) {
    fun insert(
        id: String,
        status: String,
        source: String,
    ) {
        transaction(db) {
            AcquisitionJobs.insert {
                it[AcquisitionJobs.id] = id
                it[AcquisitionJobs.status] = status
                it[AcquisitionJobs.sourceTag] = source
            }
        }
    }

    fun findById(id: String): AcquisitionJobRow? =
        transaction(db) {
            AcquisitionJobs
                .selectAll()
                .where { AcquisitionJobs.id eq id }
                .singleOrNull()
                ?.let { row ->
                    AcquisitionJobRow(
                        id = row[AcquisitionJobs.id],
                        status = row[AcquisitionJobs.status],
                        source = row[AcquisitionJobs.sourceTag],
                        createdAt = row[AcquisitionJobs.createdAt],
                    )
                }
        }
}
