package com.example.infrastructure.sqlite.columns

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Single source of truth for the float32-vector ↔ BLOB encoding used by
 * both `user_prototypes.vector` and `item_embeddings.vector`. Little-endian
 * to match the ONNX export and the wire format every consumer expects.
 *
 * Keep both functions in one file: any future migration (e.g. float16) only
 * needs one place to change, and tests can assert codec round-trip without
 * importing repository internals.
 */
object VectorCodec {
    fun encode(vector: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(vector.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        for (f in vector) buf.putFloat(f)
        return buf.array()
    }

    fun decode(bytes: ByteArray): FloatArray {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / Float.SIZE_BYTES) { buf.getFloat() }
    }
}
