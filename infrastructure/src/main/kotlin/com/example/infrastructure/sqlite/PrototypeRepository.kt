package com.example.infrastructure.sqlite

import com.example.domain.profile.PrototypePort
import com.example.domain.profile.StoredPrototype
import com.example.infrastructure.sqlite.columns.VectorCodec
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.api.ExposedBlob
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Instant

class PrototypeRepository(
    private val db: Database,
) : PrototypePort {
    override fun load(userId: Long): List<StoredPrototype> =
        transaction(db) {
            UserPrototypes
                .selectAll()
                .where { UserPrototypes.userId eq userId }
                .map { row ->
                    StoredPrototype(
                        prototypeType = row[UserPrototypes.prototypeType],
                        vector = VectorCodec.decode(row[UserPrototypes.vector].bytes),
                        weight = row[UserPrototypes.weight],
                        embeddingVersion = row[UserPrototypes.embeddingVersion],
                    )
                }
        }

    override fun replaceAll(
        userId: Long,
        rows: List<StoredPrototype>,
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
