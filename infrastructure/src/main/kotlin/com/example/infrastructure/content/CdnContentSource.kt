package com.example.infrastructure.content
import com.example.domain.content.ContentItem
import com.example.domain.content.ContentSource
import com.example.domain.content.Platform
import com.example.domain.content.RawImage
import com.example.domain.content.SourceQuery
import com.example.domain.content.md5Hex
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray

/**
 * `ContentSource` adapter for arbitrary CDN/Spaces URLs.
 *
 * Unlike the search-backed adapters, this source is single-phase:
 * [SourceQuery.tags] are treated as direct CDN URLs to download.
 * No search API, no pagination — the caller supplies the full list.
 *
 * Designed for evaluation datasets hosted on object storage (e.g.
 * DigitalOcean Spaces) where URLs are already known but the images need
 * to flow through the same acquisition pipeline as any other source.
 *
 * Rate-limited to [CDN_REQUESTS_PER_SECOND] (10 req/s) — same tier
 * as the CDN download phases of [E621ContentSource] and [UnsplashContentSource].
 */
class CdnContentSource(
    private val httpClient: HttpClient,
    private val maxImageBytes: Long = DEFAULT_MAX_IMAGE_BYTES,
    private val rateLimiter: RateLimiter = RateLimiter(CDN_REQUESTS_PER_SECOND),
) : ContentSource {
    override val platform: Platform = Platform("cdn")

    override suspend fun search(query: SourceQuery): List<ContentItem> =
        query.tags.take(query.limit).map { url ->
            ContentItem(
                platform = platform,
                sourceId = url,
                originPostUrl = url,
                cdnUrl = url,
                rating = null,
            )
        }

    override suspend fun fetch(
        query: SourceQuery,
        onImage: suspend (RawImage) -> Unit,
    ) {
        for (url in query.tags.take(query.limit)) {
            // SSRF guard: only public HTTPS targets; the client is configured with
            // redirects off so a 3xx cannot bounce a validated host to an internal one.
            val uri = UrlSafety.requirePublicHttps(url)
            val safeUrl = "${uri.scheme}://${uri.host}${uri.path}"
            rateLimiter.acquire()
            val response = httpClient.get(url)
            check(response.status == HttpStatusCode.OK) {
                "CDN fetch for $safeUrl returned ${response.status}"
            }
            // Read at most one byte past the cap so an oversized body is rejected
            // without buffering the whole download into memory.
            val bytes = response.bodyAsChannel().readRemaining(maxImageBytes + 1).readByteArray()
            check(bytes.size <= maxImageBytes) {
                "CDN fetch for $safeUrl exceeds max size ($maxImageBytes bytes)"
            }
            val md5 = md5Hex(bytes)
            onImage(
                RawImage(
                    platform = platform,
                    sourceId = url,
                    originPostUrl = url,
                    cdnUrl = url,
                    rating = null,
                    md5 = md5,
                    bytes = bytes,
                ),
            )
        }
    }

    companion object {
        const val CDN_REQUESTS_PER_SECOND: Double = 10.0

        /** Per-image download cap when the caller does not configure one (10 MiB). */
        const val DEFAULT_MAX_IMAGE_BYTES: Long = 10L * 1024 * 1024
    }
}
