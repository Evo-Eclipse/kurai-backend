package com.example.domain.cluster

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

// Format: uint32 k (LE) | uint32 dim (LE) | k x dim x float32 (LE)
internal fun loadCentroids(stream: InputStream): Array<FloatArray> {
    val headerBytes = stream.readNBytes(8)
    check(headerBytes.size == 8) { "Truncated header in centroids binary" }
    val header = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
    val k = header.int
    val dim = header.int
    check(k > 0) { "Centroids binary contains no clusters (k=$k)" }
    check(dim > 0) { "Centroids binary has zero-length vectors (dim=$dim)" }

    val floatBytes = stream.readNBytes(k * dim * 4)
    check(floatBytes.size == k * dim * 4) { "Truncated centroid data" }
    val buf = ByteBuffer.wrap(floatBytes).order(ByteOrder.LITTLE_ENDIAN)

    return Array(k) { FloatArray(dim) { buf.float } }
}
