package com.example.domain.cluster

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal const val EXPECTED_K = 23
internal const val EXPECTED_DIM = 768

// Format: uint32 k (LE) | k x 768 x float32 (LE)
internal fun loadCentroids(stream: InputStream): Array<FloatArray> {
    val headerBytes = stream.readNBytes(4)
    check(headerBytes.size == 4) { "Truncated header in centroids binary" }
    val k = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN).int
    check(k == EXPECTED_K) { "Expected $EXPECTED_K centroids, found $k" }

    val floatBytes = stream.readNBytes(k * EXPECTED_DIM * 4)
    check(floatBytes.size == k * EXPECTED_DIM * 4) { "Truncated centroid data" }
    val buf = ByteBuffer.wrap(floatBytes).order(ByteOrder.LITTLE_ENDIAN)

    return Array(k) {
        FloatArray(EXPECTED_DIM) { buf.float }
    }
}
