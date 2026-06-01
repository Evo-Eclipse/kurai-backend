package com.example.infrastructure.sqlite

import com.example.infrastructure.sqlite.columns.timestampMillis
import com.example.infrastructure.sqlite.columns.timestampMillisDefaultNow
import com.example.infrastructure.sqlite.columns.uuidText
import org.jetbrains.exposed.v1.core.Table

/**
 * Schema invariants split between SQLite and the Kotlin layer:
 *
 *  - *Structural* guards (PRIMARY KEY, NOT NULL, UNIQUE, FOREIGN KEY)
 *    live at the DB level via the column helpers under `sqlite/columns/`.
 *    They cost nothing inside an already-open transaction.
 *  - *Value* validation (enum membership, numeric ranges, business
 *    rules, whitelists of free-form keys like `source_tag`) lives in
 *    the application layer next to the consuming code. Enum-like
 *    columns carry an accompanying `object Foo { const val … }` of
 *    allowed values; repositories pass those constants.
 *
 * No SQLite-side CHECK constraints. Pushing value validation into the
 * DB makes the H2-MySQL-mode test stack brittle around CHECK semantics,
 * and Ktor is the single writer for every table — the alphabet is
 * upheld in code.
 *
 * Cross-table `embedding_version` integrity is held by the embedding
 * migration workflow rather than FK constraints — FK lookups would add
 * per-INSERT overhead on the hot acquisition path.
 *
 * All timestamps are epoch milliseconds (see TimestampColumn.kt) —
 * one unit across the schema, no per-table exceptions.
 */

object Rating {
    const val SAFE = "s"
    const val QUESTIONABLE = "q"
    const val EXPLICIT = "r"
}

object JobStatus {
    const val PENDING = "pending"
    const val RUNNING = "running"
    const val DONE = "done"
    const val FAILED = "failed"
}

object GenerationStatus {
    const val BUILDING = "building"
    const val ACTIVE = "active"
    const val DEPRECATED = "deprecated"
}

object PrototypeType {
    const val POSITIVE = "positive"
    const val NEGATIVE = "negative"
}

object EmailKind {
    const val REAL = "real"

    /** Apple Private Relay forwarder address. */
    const val RELAY = "relay"
}

object AuthProvider {
    const val EMAIL = "email"
    const val GOOGLE = "google"
    const val APPLE = "apple"

    /** Opaque, self-issued seed-phrase-style login key. */
    const val KEY = "key"
}

object Items : Table("items") {
    val id = long("id").autoIncrement()
    val md5 = text("md5").uniqueIndex()
    val url = text("url") // CDN URL where the bytes live
    val origin = text("origin") // canonical post URL on the originating platform
    val rating = text("rating").nullable() // values from `Rating`
    val embeddingVersion = text("embedding_version")
    val indexedAt = timestampMillis("indexed_at")

    override val primaryKey = PrimaryKey(id)
}

object UserEvents : Table("user_events") {
    val id = long("id").autoIncrement()
    val userId = long("user_id")
    val itemId = long("item_id")

    /** Opaque client label; the numeric weight is resolved live via `event_weights`. */
    val sourceTag = text("source_tag")

    /**
     * Snapshot of the active embedding version at event time. Kept denormalized
     * so profile-migration replay can run without joining items.
     */
    val embeddingVersion = text("embedding_version")
    val schemaVer = integer("schema_ver").default(1)
    val ts = timestampMillisDefaultNow("ts")

    override val primaryKey = PrimaryKey(id)
}

object EventWeights : Table("event_weights") {
    /** Opaque client tag; the dictionary key. */
    val sourceTag = text("source_tag")

    /** Server-resolved weight in [-1.0, 1.0]; positive = affinity, negative = aversion. */
    val weight = double("weight")
    val schemaVer = integer("schema_ver").default(1)
    val updatedAt = timestampMillis("updated_at")

    override val primaryKey = PrimaryKey(sourceTag)
}

object UserProfileState : Table("user_profile_state") {
    val userId = long("user_id")
    val embeddingVersion = text("embedding_version")
    val lastAppliedEventId = long("last_applied_event_id").default(0L)
    val updatedAt = timestampMillis("updated_at")

    override val primaryKey = PrimaryKey(userId)
}

object UserPrototypes : Table("user_prototypes") {
    val id = long("id").autoIncrement()
    val userId = long("user_id")
    val prototypeType = text("prototype_type") // values from `PrototypeType`
    val vector = blob("vector") // float32[N], little-endian; see VectorCodec
    val weight = double("weight").default(1.0)
    val embeddingVersion = text("embedding_version")
    val updatedAt = timestampMillis("updated_at")

    override val primaryKey = PrimaryKey(id)
}

object EmbeddingGenerations : Table("embedding_generations") {
    val version = text("version")
    val status = text("status") // values from `GenerationStatus`
    val onnxSha256 = text("onnx_sha256")
    val activatedAt = timestampMillis("activated_at").nullable()

    override val primaryKey = PrimaryKey(version)
}

object ClusterGenerations : Table("cluster_generations") {
    val id = long("id").autoIncrement()
    val embeddingVersion = text("embedding_version")
    val status = text("status") // values from `GenerationStatus`
    val clusterCount = integer("cluster_count")

    /** Catalog size when this generation was built; drives the rebuild trigger. */
    val catalogSizeAtBuild = long("catalog_size_at_build")
    val centroidsPath = text("centroids_path")
    val activatedAt = timestampMillis("activated_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

object IndexGenerations : Table("index_generations") {
    val id = long("id").autoIncrement()
    val embeddingVersion = text("embedding_version")
    val status = text("status") // values from `GenerationStatus`
    val indexPath = text("index_path")
    val activatedAt = timestampMillis("activated_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

object AcquisitionJobs : Table("acquisition_jobs") {
    val id = uuidText("id")
    val status = text("status") // values from `JobStatus`
    val origin = text("origin")
    val query = text("query")
    val userId = long("user_id").nullable()
    val createdAt = timestampMillisDefaultNow("created_at")
    val completedAt = timestampMillis("completed_at").nullable()
    val errorMessage = text("error_message").nullable()

    override val primaryKey = PrimaryKey(id)
}

object Users : Table("users") {
    val id = long("id").autoIncrement()
    val email = text("email").nullable().uniqueIndex()
    val emailVerifiedAt = timestampMillis("email_verified_at").nullable()
    val emailKind = text("email_kind").nullable() // values from `EmailKind`
    val createdAt = timestampMillisDefaultNow("created_at")
    val lastSeenAt = timestampMillis("last_seen_at")
    val deletedAt = timestampMillis("deleted_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

object AuthIdentities : Table("auth_identities") {
    val id = long("id").autoIncrement()
    val userId = long("user_id").references(Users.id)
    val provider = text("provider") // values from `AuthProvider`

    /**
     * Stable identifier issued by the provider:
     *  - `email` provider — the verified e-mail address itself.
     *  - `google` / `apple` — the OIDC `sub` claim.
     *  - `key` — SHA-256 hex of the opaque key (never the key).
     */
    val providerSubject = text("provider_subject")

    /** Set to disable an identity (revoke a compromised or banned key). */
    val disabledAt = timestampMillis("disabled_at").nullable()
    val createdAt = timestampMillisDefaultNow("created_at")

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(provider, providerSubject)
    }
}

object AuthSessions : Table("auth_sessions") {
    val id = uuidText("id")
    val userId = long("user_id").references(Users.id)
    val deviceLabel = text("device_label").nullable()

    /** SHA-256(refresh_token) as 64-char hex. The raw token never lands in the DB. */
    val refreshHash = text("refresh_hash")

    /**
     * Successor session id once this one is rotated on refresh. A non-null
     * value means the refresh token here is superseded; presenting it again
     * is treated as token reuse (theft) — see `AuthService.refreshSession`.
     */
    val replacedBy = uuidText("replaced_by").nullable()

    /** Updated on each refresh (rotation), not on ordinary API calls. */
    val lastUsedAt = timestampMillis("last_used_at")
    val expiresAt = timestampMillis("expires_at")
    val revokedAt = timestampMillis("revoked_at").nullable()
    val createdAt = timestampMillisDefaultNow("created_at")

    override val primaryKey = PrimaryKey(id)
}

object LoginChallenges : Table("login_challenges") {
    val id = uuidText("id")
    val email = text("email")

    /** SHA-256(otp_code) as 64-char hex. */
    val codeHash = text("code_hash")
    val expiresAt = timestampMillis("expires_at")
    val consumedAt = timestampMillis("consumed_at").nullable()

    /**
     * Count of failed verify attempts. Once it reaches the policy cap
     * (`AuthService.MAX_VERIFY_ATTEMPTS`) the challenge is dead even for
     * the correct code — caps OTP brute-force to that many guesses.
     */
    val attempts = integer("attempts").default(0)
    val createdAt = timestampMillisDefaultNow("created_at")

    override val primaryKey = PrimaryKey(id)
}

object SystemState : Table("system_state") {
    /** Always [SystemStateRepository.SINGLE_ROW_ID]; single-row invariant upheld in Kotlin. */
    val id = integer("id")

    /** Embedding version served to new users; the system-wide default. */
    val defaultEmbeddingVersion = text("default_embedding_version").nullable()

    /** Pointer to the active row in `cluster_generations`. */
    val activeClusterId = long("active_cluster_id").nullable()

    /** Pointer to the active row in `index_generations`. */
    val activeIndexId = long("active_index_id").nullable()

    /** System-written catalog counters (wired in a later wave). */
    val totalItems = long("total_items").default(0L)
    val embeddedItems = long("embedded_items").default(0L)
    val updatedAt = timestampMillis("updated_at")

    override val primaryKey = PrimaryKey(id)
}

object RuntimeConfigs : Table("runtime_config") {
    val key = text("key")

    /** Discriminator: int | long | real | bool | string. See `ConfigKey.type`. */
    val valueType = text("value_type")

    /** Raw string form; decoded against `valueType` at read time. */
    val value = text("value")
    val updatedAt = timestampMillis("updated_at")

    override val primaryKey = PrimaryKey(key)
}
