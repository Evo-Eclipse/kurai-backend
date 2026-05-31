package com.example.application.config

/**
 * The closed alphabet of value-type discriminators that may appear
 * in `runtime_config.value_type`. Mirrors what the SQLite row holds
 * as a string and what a [ConfigKey] declares it expects, so the
 * accessor can cross-check them in one place.
 */
enum class ValueType(
    val wire: String,
) {
    INT("int"),
    LONG("long"),
    REAL("real"),
    BOOL("bool"),
    STRING("string"),
}

/**
 * Typed key for a [RuntimeConfig] entry. The [type] discriminator
 * must match the row's stored `value_type`; if they drift, [RuntimeConfig.get]
 * fails fast with a message that names both sides.
 *
 * Adding a new operator-tunable parameter means:
 *  1. Append a new `object` here with key, type, and decoder.
 *  2. Seed a default at startup (Application.kt) so the first
 *     `get` does not blow up on a fresh database.
 *
 * Keep [key] unique across the file. The string form ("auth.session_ttl_ms",
 * "shuttle.allowed_hosts", …) is also the SQLite PK — renaming is a
 * data migration.
 */
sealed class ConfigKey<T>(
    val key: String,
    val type: ValueType,
    val decode: (String) -> T,
) {
    /** Lifetime of an `auth_sessions` row before refresh stops working. */
    data object AuthSessionTtlMs : ConfigKey<Long>(
        key = "auth.session_ttl_ms",
        type = ValueType.LONG,
        decode = String::toLong,
    )

    /** Lifetime of a magic-link / OTP challenge before verify rejects it. */
    data object AuthChallengeTtlMs : ConfigKey<Long>(
        key = "auth.challenge_ttl_ms",
        type = ValueType.LONG,
        decode = String::toLong,
    )
}
