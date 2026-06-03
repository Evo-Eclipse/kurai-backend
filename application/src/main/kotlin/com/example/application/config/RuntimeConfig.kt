package com.example.application.config

import com.example.domain.config.RuntimeConfigPort

/**
 * Typed reader / writer for the `runtime_config` table. Wraps the
 * raw [RuntimeConfigPort] so the value-type discriminator
 * declared on a [ConfigKey] is checked against the row at every
 * read — keeping the "three invariants" problem from re-appearing
 * (Kotlin-side type, DB-stored type, parsed value).
 */
class RuntimeConfig(
    private val repo: RuntimeConfigPort,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    fun <T> get(key: ConfigKey<T>): T {
        val row =
            repo.load(key.key)
                ?: error("Missing runtime config: ${key.key}. Seed it at startup before resolving dependents.")
        check(row.valueType == key.type.wire) {
            "Config ${key.key}: DB declares value_type=${row.valueType} but accessor expects ${key.type.wire}"
        }
        return runCatching { key.decode(row.value) }.getOrElse { cause ->
            error("Config ${key.key}: cannot decode value '${row.value}' as ${key.type.wire} — ${cause.message}")
        }
    }

    fun seedIfMissing(
        key: ConfigKey<*>,
        value: String,
    ): Boolean {
        if (repo.load(key.key) != null) return false
        repo.upsert(key.key, key.type.wire, value, clock())
        return true
    }

    fun <T> set(
        key: ConfigKey<T>,
        value: T,
    ) {
        repo.upsert(key.key, key.type.wire, value.toString(), clock())
    }
}
