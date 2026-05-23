package com.example.domain.embedding

typealias InferPort = suspend (tensor: FloatArray) -> FloatArray

typealias EmbedLookupPort = suspend (itemIds: List<Long>) -> Map<Long, FloatArray>

typealias EmbedStorePort = suspend (itemId: Long, vector: FloatArray) -> Unit

typealias IndexSearchPort = suspend (query: FloatArray, k: Int) -> List<Long>
