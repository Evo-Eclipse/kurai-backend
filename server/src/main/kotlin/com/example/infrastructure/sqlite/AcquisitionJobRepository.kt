package com.example.infrastructure.sqlite

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

data class AcquisitionJobRow(
    val id: String,
    val status: String,
    val origin: String,
    val query: String,
    val userId: Long?,
    val createdAt: Long,
    val completedAt: Long?,
)

class AcquisitionJobRepository(
    private val db: Database,
) {
    fun insert(
        id: String,
        status: String,
        origin: String,
        query: String,
        userId: Long? = null,
    ) {
        transaction(db) {
            AcquisitionJobs.insert {
                it[AcquisitionJobs.id] = id
                it[AcquisitionJobs.status] = status
                it[AcquisitionJobs.origin] = origin
                it[AcquisitionJobs.query] = query
                it[AcquisitionJobs.userId] = userId
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
                        origin = row[AcquisitionJobs.origin],
                        query = row[AcquisitionJobs.query],
                        userId = row[AcquisitionJobs.userId],
                        createdAt = row[AcquisitionJobs.createdAt],
                        completedAt = row[AcquisitionJobs.completedAt],
                    )
                }
        }
}
