package com.example.infrastructure.sqlite

import com.example.domain.catalog.AcquisitionJob
import com.example.domain.catalog.AcquisitionJobPort
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

class AcquisitionJobRepository(
    private val db: Database,
) : AcquisitionJobPort {
    override fun insert(
        id: String,
        status: String,
        origin: String,
        query: String,
        userId: Long?,
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

    override fun updateStatus(
        id: String,
        status: String,
        completedAt: Long?,
        errorMessage: String?,
    ) {
        transaction(db) {
            AcquisitionJobs.update({ AcquisitionJobs.id eq id }) {
                it[AcquisitionJobs.status] = status
                if (completedAt != null) it[AcquisitionJobs.completedAt] = completedAt
                if (errorMessage != null) it[AcquisitionJobs.errorMessage] = errorMessage
            }
        }
    }

    override fun findById(id: String): AcquisitionJob? =
        transaction(db) {
            AcquisitionJobs
                .selectAll()
                .where { AcquisitionJobs.id eq id }
                .singleOrNull()
                ?.let { row ->
                    AcquisitionJob(
                        id = row[AcquisitionJobs.id],
                        status = row[AcquisitionJobs.status],
                        origin = row[AcquisitionJobs.origin],
                        query = row[AcquisitionJobs.query],
                        userId = row[AcquisitionJobs.userId],
                        createdAt = row[AcquisitionJobs.createdAt],
                        completedAt = row[AcquisitionJobs.completedAt],
                        errorMessage = row[AcquisitionJobs.errorMessage],
                    )
                }
        }
}
