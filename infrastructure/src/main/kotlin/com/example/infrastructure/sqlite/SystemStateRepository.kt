package com.example.infrastructure.sqlite

import com.example.domain.catalog.GlobalSystemState
import com.example.domain.catalog.SystemStatePort
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

class SystemStateRepository(
    private val db: Database,
) : SystemStatePort {
    override fun seedIfMissing(now: Long) {
        transaction(db) {
            val present =
                SystemState
                    .selectAll()
                    .where { SystemState.id eq SINGLE_ROW_ID }
                    .firstOrNull() != null
            if (!present) {
                SystemState.insert {
                    it[id] = SINGLE_ROW_ID
                    it[updatedAt] = now
                }
            }
        }
    }

    override fun read(): GlobalSystemState =
        transaction(db) {
            SystemState
                .selectAll()
                .where { SystemState.id eq SINGLE_ROW_ID }
                .firstOrNull()
                ?.let {
                    GlobalSystemState(
                        defaultEmbeddingVersion = it[SystemState.defaultEmbeddingVersion],
                        activeClusterId = it[SystemState.activeClusterId],
                        activeIndexId = it[SystemState.activeIndexId],
                        totalItems = it[SystemState.totalItems],
                        embeddedItems = it[SystemState.embeddedItems],
                        updatedAt = it[SystemState.updatedAt],
                    )
                }
                ?: error("system_state row missing; seedIfMissing must run at startup")
        }

    override fun setDefaultEmbeddingVersion(
        version: String,
        now: Long,
    ) {
        transaction(db) {
            EmbeddingGenerations.update({ EmbeddingGenerations.status eq GenerationStatus.ACTIVE }) {
                it[status] = GenerationStatus.DEPRECATED
            }
            EmbeddingGenerations.update({ EmbeddingGenerations.version eq version }) {
                it[status] = GenerationStatus.ACTIVE
                it[activatedAt] = now
            }
            SystemState.update({ SystemState.id eq SINGLE_ROW_ID }) {
                it[defaultEmbeddingVersion] = version
                it[updatedAt] = now
            }
        }
    }

    override fun activateCluster(
        clusterId: Long,
        now: Long,
    ) {
        transaction(db) {
            ClusterGenerations.update({ ClusterGenerations.status eq GenerationStatus.ACTIVE }) {
                it[status] = GenerationStatus.DEPRECATED
            }
            ClusterGenerations.update({ ClusterGenerations.id eq clusterId }) {
                it[status] = GenerationStatus.ACTIVE
                it[activatedAt] = now
            }
            SystemState.update({ SystemState.id eq SINGLE_ROW_ID }) {
                it[activeClusterId] = clusterId
                it[updatedAt] = now
            }
        }
    }

    override fun activateIndex(
        indexId: Long,
        now: Long,
    ) {
        transaction(db) {
            IndexGenerations.update({ IndexGenerations.status eq GenerationStatus.ACTIVE }) {
                it[status] = GenerationStatus.DEPRECATED
            }
            IndexGenerations.update({ IndexGenerations.id eq indexId }) {
                it[status] = GenerationStatus.ACTIVE
                it[activatedAt] = now
            }
            SystemState.update({ SystemState.id eq SINGLE_ROW_ID }) {
                it[activeIndexId] = indexId
                it[updatedAt] = now
            }
        }
    }

    override fun setCounts(
        totalItems: Long,
        embeddedItems: Long,
        now: Long,
    ) {
        transaction(db) {
            SystemState.update({ SystemState.id eq SINGLE_ROW_ID }) {
                it[SystemState.totalItems] = totalItems
                it[SystemState.embeddedItems] = embeddedItems
                it[updatedAt] = now
            }
        }
    }

    companion object {
        const val SINGLE_ROW_ID = 1
    }
}
