package com.example.infrastructure.sqlite

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

data class ClusterGenerationRow(
    val id: Long,
    val embeddingVersion: String,
    val status: String,
    val clusterCount: Int,
    val catalogSizeAtBuild: Long,
    val centroidsPath: String,
    val activatedAt: Long?,
)

/**
 * Tracks k-means cluster builds in `cluster_generations`. A build is
 * registered with [createBuilding] (status `building`), then promoted by
 * [SystemStateRepository.activateCluster], which flips its status and
 * repoints `system_state.active_cluster_id` in one transaction. Which
 * generation is *active* is read from that pointer, not scanned here.
 */
class ClusterGenerationRepository(
    private val db: Database,
) {
    /** Registers a new `building` generation and returns its id. */
    fun createBuilding(
        embeddingVersion: String,
        clusterCount: Int,
        catalogSizeAtBuild: Long,
        centroidsPath: String,
    ): Long =
        transaction(db) {
            ClusterGenerations.insert {
                it[ClusterGenerations.embeddingVersion] = embeddingVersion
                it[status] = GenerationStatus.BUILDING
                it[ClusterGenerations.clusterCount] = clusterCount
                it[ClusterGenerations.catalogSizeAtBuild] = catalogSizeAtBuild
                it[ClusterGenerations.centroidsPath] = centroidsPath
            }[ClusterGenerations.id]
        }

    fun findById(id: Long): ClusterGenerationRow? =
        transaction(db) {
            ClusterGenerations
                .selectAll()
                .where { ClusterGenerations.id eq id }
                .firstOrNull()
                ?.let {
                    ClusterGenerationRow(
                        id = it[ClusterGenerations.id],
                        embeddingVersion = it[ClusterGenerations.embeddingVersion],
                        status = it[ClusterGenerations.status],
                        clusterCount = it[ClusterGenerations.clusterCount],
                        catalogSizeAtBuild = it[ClusterGenerations.catalogSizeAtBuild],
                        centroidsPath = it[ClusterGenerations.centroidsPath],
                        activatedAt = it[ClusterGenerations.activatedAt],
                    )
                }
        }
}
