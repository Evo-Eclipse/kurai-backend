package com.example.infrastructure.sqlite

import com.example.infrastructure.sqlite.columns.timestampMillis
import com.example.infrastructure.sqlite.columns.timestampMillisDefaultNow
import com.example.infrastructure.sqlite.columns.timestampSeconds
import com.example.infrastructure.sqlite.columns.timestampSecondsDefaultNow
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
 * Timestamp policy is mixed by design (see TimestampColumn.kt):
 * pre-existing tables keep epoch seconds to avoid breaking on-disk
 * data; new tables (auth_*, future runtime_config) use epoch millis.
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
}

object Items : Table("items") {
    val id = long("id").autoIncrement()
    val md5 = text("md5").uniqueIndex()
    val url = text("url") // CDN URL where the bytes live
    val origin = text("origin") // canonical post URL on the originating platform
    val rating = text("rating").nullable() // values from `Rating`
    val embeddingVersion = text("embedding_version")
    val indexedAt = timestampSeconds("indexed_at")

    override val primaryKey = PrimaryKey(id)
}

object UserEvents : Table("user_events") {
    val id = long("id").autoIncrement()
    val userId = long("user_id")
    val itemId = long("item_id")
    val weight = float("weight") // [-1.0, 1.0]; positive = affinity, negative = aversion

    /**
     * Snapshot of the active embedding version at event time. Kept denormalized
     * so profile-migration replay can run without joining items.
     */
    val embeddingVersion = text("embedding_version")
    val ts = timestampSecondsDefaultNow("ts")

    override val primaryKey = PrimaryKey(id)
}

object UserProfileState : Table("user_profile_state") {
    val userId = long("user_id")
    val embeddingVersion = text("embedding_version")
    val lastAppliedEventId = long("last_applied_event_id").default(0L)
    val updatedAt = timestampSeconds("updated_at")

    override val primaryKey = PrimaryKey(userId)
}

object UserPrototypes : Table("user_prototypes") {
    val id = long("id").autoIncrement()
    val userId = long("user_id")
    val prototypeType = text("prototype_type") // values from `PrototypeType`
    val vector = blob("vector") // float32[N], little-endian; see VectorCodec
    val weight = double("weight").default(1.0)
    val embeddingVersion = text("embedding_version")
    val updatedAt = timestampSeconds("updated_at")

    override val primaryKey = PrimaryKey(id)
}

object EmbeddingGenerations : Table("embedding_generations") {
    val version = text("version")
    val status = text("status") // values from `GenerationStatus`
    val onnxSha256 = text("onnx_sha256")
    val clusterCount = integer("cluster_count").nullable()
    val clusterUpdatedAt = timestampSeconds("cluster_updated_at").nullable()
    val activatedAt = timestampSeconds("activated_at").nullable()

    override val primaryKey = PrimaryKey(version)
}

object IndexGenerations : Table("index_generations") {
    val id = long("id").autoIncrement()
    val embeddingVersion = text("embedding_version")
    val status = text("status") // values from `GenerationStatus`
    val indexPath = text("index_path")
    val activatedAt = timestampSeconds("activated_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

object AcquisitionJobs : Table("acquisition_jobs") {
    val id = uuidText("id")
    val status = text("status") // values from `JobStatus`
    val origin = text("origin")
    val query = text("query")
    val userId = long("user_id").nullable()
    val createdAt = timestampSecondsDefaultNow("created_at")
    val completedAt = timestampSeconds("completed_at").nullable()
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
     */
    val providerSubject = text("provider_subject")
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
    val createdAt = timestampMillisDefaultNow("created_at")

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
