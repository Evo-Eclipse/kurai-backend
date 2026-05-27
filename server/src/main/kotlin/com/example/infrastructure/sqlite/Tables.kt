package com.example.infrastructure.sqlite

import org.jetbrains.exposed.v1.core.Table
import java.time.Instant

/**
 * Allowed values for fixed-vocabulary columns are documented inline next to
 * each column. Validation is enforced in the Kotlin domain layer (smart
 * constructors on event/rating/status types), not at the DB level — keeps
 * the schema portable across SQLite/H2 and avoids dialect-specific CHECK
 * quirks. Cross-table `embedding_version` integrity is held by the
 * embedding-migration workflow rather than FK constraints (FK lookups would
 * add per-INSERT overhead on the hot acquisition path).
 */

object Items : Table("items") {
    val id = long("id").autoIncrement()
    val md5 = text("md5").uniqueIndex()
    val url = text("url") // CDN URL where the bytes live
    val origin = text("origin") // canonical post URL on the originating platform
    val rating = text("rating").nullable() // s | q | r
    val embeddingVersion = text("embedding_version")
    val indexedAt = long("indexed_at")

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
    val ts =
        long("ts").clientDefault {
            Instant
                .now()
                .epochSecond
        }

    override val primaryKey = PrimaryKey(id)
}

object UserProfileState : Table("user_profile_state") {
    val userId = long("user_id")
    val embeddingVersion = text("embedding_version")
    val lastAppliedEventId = long("last_applied_event_id").default(0L)
    val updatedAt = long("updated_at")

    override val primaryKey = PrimaryKey(userId)
}

object UserPrototypes : Table("user_prototypes") {
    val id = long("id").autoIncrement()
    val userId = long("user_id")
    val prototypeType = text("prototype_type") // positive | negative
    val vector = blob("vector") // float32[768], little-endian
    val weight = double("weight").default(1.0)
    val embeddingVersion = text("embedding_version")
    val updatedAt = long("updated_at")

    override val primaryKey = PrimaryKey(id)
}

object EmbeddingGenerations : Table("embedding_generations") {
    val version = text("version")
    val status = text("status") // building | active | deprecated
    val onnxSha256 = text("onnx_sha256")
    val clusterCount = integer("cluster_count").nullable()
    val clusterUpdatedAt = long("cluster_updated_at").nullable()
    val activatedAt = long("activated_at").nullable()

    override val primaryKey = PrimaryKey(version)
}

object IndexGenerations : Table("index_generations") {
    val id = long("id").autoIncrement()
    val embeddingVersion = text("embedding_version")
    val status = text("status") // building | active | deprecated
    val indexPath = text("index_path")
    val activatedAt = long("activated_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

object AcquisitionJobs : Table("acquisition_jobs") {
    val id = text("id")
    val status = text("status") // pending | running | done | failed
    val origin = text("origin")
    val query = text("query")
    val userId = long("user_id").nullable()
    val createdAt =
        long("created_at").clientDefault {
            Instant
                .now()
                .epochSecond
        }
    val completedAt = long("completed_at").nullable()
    val errorMessage = text("error_message").nullable()

    override val primaryKey = PrimaryKey(id)
}
