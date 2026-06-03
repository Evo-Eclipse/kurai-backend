package com.example.infrastructure.sqlite

import com.example.domain.catalog.ClusterGeneration
import com.example.domain.catalog.ClusterGenerationPort
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll

class ClusterGenerationRepository(
    private val db: Database,
) : ClusterGenerationPort {
    override suspend fun createBuilding(
        embeddingVersion: String,
        clusterCount: Int,
        catalogSizeAtBuild: Long,
        centroidsPath: String,
    ): Long =
        sqliteTransaction(db) {
            ClusterGenerations.insert {
                it[ClusterGenerations.embeddingVersion] = embeddingVersion
                it[status] = GenerationStatus.BUILDING
                it[ClusterGenerations.clusterCount] = clusterCount
                it[ClusterGenerations.catalogSizeAtBuild] = catalogSizeAtBuild
                it[ClusterGenerations.centroidsPath] = centroidsPath
            }[ClusterGenerations.id]
        }

    override suspend fun findById(id: Long): ClusterGeneration? =
        sqliteTransaction(db) {
            ClusterGenerations
                .selectAll()
                .where { ClusterGenerations.id eq id }
                .firstOrNull()
                ?.let {
                    ClusterGeneration(
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
