package com.example.infrastructure.sqlite

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

data class RuntimeConfigRow(
    val key: String,
    val valueType: String,
    val value: String,
    val updatedAt: Long,
)

/**
 * Raw key/value access for `runtime_config`. Typed lookups happen
 * one layer up via `application/config/RuntimeConfig` so the type
 * discriminator and the parser stay in lockstep.
 */
class RuntimeConfigRepository(
    private val db: Database,
) {
    fun load(key: String): RuntimeConfigRow? =
        transaction(db) {
            RuntimeConfigs
                .selectAll()
                .where { RuntimeConfigs.key eq key }
                .firstOrNull()
                ?.let {
                    RuntimeConfigRow(
                        key = it[RuntimeConfigs.key],
                        valueType = it[RuntimeConfigs.valueType],
                        value = it[RuntimeConfigs.value],
                        updatedAt = it[RuntimeConfigs.updatedAt],
                    )
                }
        }

    fun upsert(
        key: String,
        valueType: String,
        value: String,
        now: Long,
    ) {
        transaction(db) {
            val updated =
                RuntimeConfigs.update({ RuntimeConfigs.key eq key }) {
                    it[RuntimeConfigs.valueType] = valueType
                    it[RuntimeConfigs.value] = value
                    it[RuntimeConfigs.updatedAt] = now
                }
            if (updated == 0) {
                RuntimeConfigs.insert {
                    it[RuntimeConfigs.key] = key
                    it[RuntimeConfigs.valueType] = valueType
                    it[RuntimeConfigs.value] = value
                    it[RuntimeConfigs.updatedAt] = now
                }
            }
        }
    }
}
