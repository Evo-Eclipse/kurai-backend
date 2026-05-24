package com.example.infrastructure.sqlite

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import java.time.Instant

object Items : Table("items") {
    val id = long("id").autoIncrement()
    val md5 = text("md5").uniqueIndex()
    val sourceTag = text("source")
    val sourceId = text("source_id")
    val embeddingVersion = text("embedding_version")
    val indexedAt = text("indexed_at")

    override val primaryKey = PrimaryKey(id)
}

object UserEvents : Table("user_events") {
    val id = long("id").autoIncrement()
    val userId = long("user_id")
    val itemId = long("item_id")
    val eventType = text("event_type")
    val embeddingVersion = text("embedding_version")
    val ts =
        text("ts").clientDefault {
            Instant
                .now()
                .toString()
        }

    override val primaryKey = PrimaryKey(id)
}

object UserProfileState : Table("user_profile_state") {
    val userId = long("user_id")
    val embeddingVersion = text("embedding_version")
    val lastAppliedEventId = long("last_applied_event_id").default(0L)
    val updatedAt = text("updated_at")

    override val primaryKey = PrimaryKey(userId)
}

object UserPrototypes : Table("user_prototypes") {
    val id = long("id").autoIncrement()
    val userId = long("user_id")
    val prototypeType = text("prototype_type")
    val vector = blob("vector") // float32[768], little-endian
    val weight = double("weight").default(1.0)
    val embeddingVersion = text("embedding_version")
    val updatedAt = text("updated_at")

    override val primaryKey = PrimaryKey(id)
}

object EmbeddingGenerations : Table("embedding_generations") {
    val version = text("version")
    val status = text("status")
    val onnxSha256 = text("onnx_sha256")
    val activatedAt = text("activated_at").nullable()

    override val primaryKey = PrimaryKey(version)
}

object IndexGenerations : Table("index_generations") {
    val id = long("id").autoIncrement()
    val embeddingVersion = text("embedding_version")
    val status = text("status")
    val indexPath = text("index_path")
    val activatedAt = text("activated_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

// Singleton table: always exactly one row with id = 1.
object CatalogGrowth : Table("catalog_growth") {
    val id = integer("id").check { it eq 1 }
    val lastClusterUpdate = text("last_cluster_update")
    val lastEmbeddingCount = long("last_embedding_count")
    val currentEmbeddingCount = long("current_embedding_count")

    override val primaryKey = PrimaryKey(id)
}

object AcquisitionJobs : Table("acquisition_jobs") {
    val id = text("id") // UUID
    val status = text("status") // pending | running | done | failed
    val sourceTag = text("source")
    val createdAt =
        text("created_at").clientDefault {
            Instant
                .now()
                .toString()
        }

    override val primaryKey = PrimaryKey(id)
}
