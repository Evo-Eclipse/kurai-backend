package com.example.infrastructure.content

import kotlinx.coroutines.flow.Flow

/**
 * Stable identifier of where a piece of content came from. Used as the
 * `origin` column in `items`, and lets a single Lucene index hold
 * mixed-source catalogues without colliding ids.
 *
 * Adapters are wired by string identifier rather than by reified Kotlin
 * type so that the acquisition pipeline stays open for new sources —
 * adding a platform is a new [ContentSource] implementation registered
 * in DI under its own [Platform.id], no changes to the pipeline.
 */
@JvmInline
value class Platform(
    val id: String,
) {
    init {
        require(id.isNotBlank()) { "Platform id must be non-blank" }
    }
}

/** Tag-based search query. */
data class SourceQuery(
    val tags: List<String>,
    val limit: Int,
) {
    init {
        require(limit > 0) { "limit must be positive, got $limit" }
    }
}

/**
 * One image fetched from a [ContentSource]. Kept deliberately minimal:
 * everything beyond the content bytes (dimensions, EXIF, tags) is
 * source-specific and can be added on-demand. Acquisition only needs the
 * bytes and the cross-source-stable triple `(md5, originPostUrl, cdnUrl)`.
 */
data class RawImage(
    val platform: Platform,
    /** Source-local identifier, unique within [platform]. */
    val sourceId: String,
    /** Canonical post URL on the source platform (UI/debug use). */
    val originPostUrl: String,
    /** Direct CDN URL for the bytes (for re-fetch on object-store miss). */
    val cdnUrl: String,
    /** Source-supplied content rating, e.g. `s|q|r`; null when not provided. */
    val rating: String?,
    /** MD5 reported by the source. Verified against actual bytes downstream. */
    val md5: String,
    /** Raw image bytes (JPEG/PNG/WebP, decoded by [ImagePreprocessor]). */
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

/**
 * Pluggable content-source adapter. Each implementation handles its own
 * authentication, rate limiting and pagination; the acquisition pipeline
 * consumes them through this single contract.
 *
 * The MVP `ContentSourcePort` typealias from `RECALL.md` will live in
 * `domain.content` once that wave lands; this orange-side interface is
 * the seam the typealias will alias to.
 */
interface ContentSource {
    val platform: Platform

    /**
     * Streams images matching [query]. The flow respects the source's
     * rate limit and yields up to `query.limit` items before completing.
     */
    fun fetch(query: SourceQuery): Flow<RawImage>
}
