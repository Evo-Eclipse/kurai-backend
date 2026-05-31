package com.example.application.config

import com.example.infrastructure.sqlite.RuntimeConfigRepository

/**
 * Typed reader / writer for the `runtime_config` table. Wraps the
 * raw [RuntimeConfigRepository] so the value-type discriminator
 * declared on a [ConfigKey] is checked against the row at every
 * read — keeping the "three invariants" problem from re-appearing
 * (Kotlin-side type, DB-stored type, parsed value).
 *
 * Reads go straight to SQLite on every call. Add caching only if a
 * profile shows it matters; for the auth TTLs this is a once-per-
 * request lookup against an indexed PK.
 */
class RuntimeConfig(
    private val repo: RuntimeConfigRepository,
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

    /**
     * Idempotent seed. Stores `value` only when [key] is absent;
     * existing operator-set values are preserved across restarts.
     * Returns true when a row was inserted, false when one already
     * existed.
     */
    fun seedIfMissing(
        key: ConfigKey<*>,
        value: String,
    ): Boolean {
        if (repo.load(key.key) != null) return false
        repo.upsert(key.key, key.type.wire, value, clock())
        return true
    }

    /** Operator-driven update (kept here for symmetry; not yet used). */
    fun <T> set(
        key: ConfigKey<T>,
        value: T,
    ) {
        repo.upsert(key.key, key.type.wire, value.toString(), clock())
    }
}
