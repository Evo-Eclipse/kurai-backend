package com.example.domain.embedding

// I-3 boundary: ranking path uses only lookup, never infer.
typealias EmbedLookupPort = suspend (itemIds: List<Long>) -> Map<Long, FloatArray>

// Separate infer port used only by InferenceService, never by ranking.
typealias InferPort = suspend (tensor: FloatArray) -> FloatArray
