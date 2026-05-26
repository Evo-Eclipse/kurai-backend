package com.example.domain.embedding

// I-3 boundary: ranking path uses only lookup, never infer.
typealias EmbedLookupPort = (itemIds: List<Long>) -> Map<Long, FloatArray>

// Separate infer port used only by InferenceService, never by ranking.
typealias InferPort = (tensor: FloatArray) -> FloatArray
