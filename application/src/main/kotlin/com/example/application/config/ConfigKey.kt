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
 * Typed key for a [RuntimeConfig] entry.
 *
 * The discriminator [type] and the [decode] parser are bound together by
 * the typed base classes ([LongKey] and its siblings): a key cannot
 * declare a `value_type` that disagrees with its parser, because choosing
 * a base class fixes both at once. That removes the last hand-maintained
 * pairing — there is no longer a separate `type` field and `decode` lambda
 * to keep in sync. [RuntimeConfig.get] still cross-checks [type] against
 * the stored row, so a value that drifted in the DB fails fast.
 *
 * Adding a new operator-tunable parameter means:
 *  1. Append a `data object` below, extending the base class for its
 *     type (add the base class — `IntKey`, `RealKey`, … — if this is the
 *     first key of that type; each binds one [ValueType] to its parser).
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
) {
    abstract fun decode(raw: String): T

    /** Keys whose value is stored as [ValueType.LONG] and parsed with `toLong`. */
    abstract class LongKey(
        key: String,
    ) : ConfigKey<Long>(key, ValueType.LONG) {
        final override fun decode(raw: String): Long = raw.toLong()
    }

    /** Keys whose value is stored as [ValueType.INT] and parsed with `toInt`. */
    abstract class IntKey(
        key: String,
    ) : ConfigKey<Int>(key, ValueType.INT) {
        final override fun decode(raw: String): Int = raw.toInt()
    }

    /** Lifetime of an `auth_sessions` row before refresh stops working. */
    data object AuthSessionTtlMs : LongKey(key = "auth.session_ttl_ms")

    /** Lifetime of a magic-link / OTP challenge before verify rejects it. */
    data object AuthChallengeTtlMs : LongKey(key = "auth.challenge_ttl_ms")

    /** Lifetime of a minted access JWT. */
    data object AuthJwtTtlMs : LongKey(key = "auth.jwt_ttl_ms")

    /** How often the profile-persist worker flushes dirty profiles. */
    data object ProfilePersistIntervalMs : LongKey(key = "profile.persist_interval_ms")

    /** How often the k-means scheduler checks whether to retrain clusters. */
    data object KMeansCheckIntervalMs : LongKey(key = "cluster.kmeans_check_interval_ms")

    /** How often expired auth sessions are swept. */
    data object SessionGcIntervalMs : LongKey(key = "session.gc_interval_ms")

    /** Grace past `expires_at` before a session row is purged. */
    data object SessionGcRetentionMs : LongKey(key = "session.gc_retention_ms")

    /** Max key-issuance requests allowed per client IP per window. */
    data object KeyIssueRateLimitMax : IntKey(key = "key_issue.rate_limit_max")

    /** Length of the key-issuance rate-limit window. */
    data object KeyIssueRateLimitWindowMs : LongKey(key = "key_issue.rate_limit_window_ms")

    /** Interval between prototype split runs (worker). */
    data object ProtoSplitIntervalMs : LongKey(key = "proto_split.interval_ms")
}
