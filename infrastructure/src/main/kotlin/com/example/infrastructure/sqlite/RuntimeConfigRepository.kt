package com.example.infrastructure.sqlite

import com.example.domain.config.RuntimeConfigEntry
import com.example.domain.config.RuntimeConfigPort
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

class RuntimeConfigRepository(
    private val db: Database,
) : RuntimeConfigPort {
    override fun load(key: String): RuntimeConfigEntry? =
        transaction(db) {
            RuntimeConfigs
                .selectAll()
                .where { RuntimeConfigs.key eq key }
                .firstOrNull()
                ?.let {
                    RuntimeConfigEntry(
                        key = it[RuntimeConfigs.key],
                        valueType = it[RuntimeConfigs.valueType],
                        value = it[RuntimeConfigs.value],
                        updatedAt = it[RuntimeConfigs.updatedAt],
                    )
                }
        }

    override fun upsert(
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
