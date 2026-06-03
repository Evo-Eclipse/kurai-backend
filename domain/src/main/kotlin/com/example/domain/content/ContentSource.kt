package com.example.domain.content

import kotlinx.coroutines.flow.Flow

@JvmInline
value class Platform(
    val id: String,
) {
    init {
        require(id.isNotBlank()) { "Platform id must be non-blank" }
    }
}

data class SourceQuery(
    val tags: List<String>,
    val limit: Int,
) {
    init {
        require(limit > 0) { "limit must be positive, got $limit" }
    }
}

data class RawImage(
    val platform: Platform,
    val sourceId: String,
    val originPostUrl: String,
    val cdnUrl: String,
    val rating: String?,
    val md5: String,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is RawImage &&
            platform == other.platform &&
            sourceId == other.sourceId &&
            originPostUrl == other.originPostUrl &&
            cdnUrl == other.cdnUrl &&
            rating == other.rating &&
            md5 == other.md5 &&
            bytes.contentEquals(other.bytes)

    override fun hashCode(): Int {
        var result = platform.hashCode()
        result = 31 * result + sourceId.hashCode()
        result = 31 * result + originPostUrl.hashCode()
        result = 31 * result + cdnUrl.hashCode()
        result = 31 * result + (rating?.hashCode() ?: 0)
        result = 31 * result + md5.hashCode()
        result = 31 * result + bytes.contentHashCode()
        return result
    }
}

interface ContentSource {
    val platform: Platform

    fun fetch(query: SourceQuery): Flow<RawImage>
}
