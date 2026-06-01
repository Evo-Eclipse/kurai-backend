package com.example.infrastructure.sqlite

import com.example.infrastructure.sqlite.columns.VectorCodec
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.api.ExposedBlob
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Instant

data class PrototypeRow(
    val prototypeType: String,
    val vector: FloatArray,
    val weight: Double,
    val embeddingVersion: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PrototypeRow) return false
        return prototypeType == other.prototypeType &&
            vector.contentEquals(other.vector) &&
            weight == other.weight &&
            embeddingVersion == other.embeddingVersion
    }

    override fun hashCode(): Int {
        var result = prototypeType.hashCode()
        result = 31 * result + vector.contentHashCode()
        result = 31 * result + weight.hashCode()
        result = 31 * result + embeddingVersion.hashCode()
        return result
    }
}

class PrototypeRepository(
    private val db: Database,
) {
    fun load(userId: Long): List<PrototypeRow> =
        transaction(db) {
            UserPrototypes
                .selectAll()
                .where { UserPrototypes.userId eq userId }
                .map { row ->
                    PrototypeRow(
                        prototypeType = row[UserPrototypes.prototypeType],
                        vector = VectorCodec.decode(row[UserPrototypes.vector].bytes),
                        weight = row[UserPrototypes.weight],
                        embeddingVersion = row[UserPrototypes.embeddingVersion],
                    )
                }
        }

    fun replaceAll(
        userId: Long,
        rows: List<PrototypeRow>,
    ) {
        transaction(db) {
            UserPrototypes.deleteWhere { UserPrototypes.userId eq userId }
            if (rows.isNotEmpty()) {
                val now = Instant.now().toEpochMilli()
                UserPrototypes.batchInsert(rows) { r ->
                    this[UserPrototypes.userId] = userId
                    this[UserPrototypes.prototypeType] = r.prototypeType
                    this[UserPrototypes.vector] = ExposedBlob(VectorCodec.encode(r.vector))
                    this[UserPrototypes.weight] = r.weight
                    this[UserPrototypes.embeddingVersion] = r.embeddingVersion
                    this[UserPrototypes.updatedAt] = now
                }
            }
        }
    }
}
