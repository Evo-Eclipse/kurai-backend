package com.example.domain.embedding

// I-3 boundary: ranking path uses only lookup, never infer.
typealias EmbedLookupPort = (itemIds: List<Long>) -> Map<Long, FloatArray>
