package com.example.infrastructure.content

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.security.MessageDigest

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
    private val rateLimiter: RateLimiter = RateLimiter(CDN_REQUESTS_PER_SECOND),
) : ContentSource {
    override val platform: Platform = Platform("cdn")

    override fun fetch(query: SourceQuery): Flow<RawImage> =
        flow {
            for (url in query.tags.take(query.limit)) {
                rateLimiter.acquire()
                val response = httpClient.get(url)
                check(response.status == HttpStatusCode.OK) {
                    val safeUrl = java.net.URI.create(url).let { "${it.scheme}://${it.host}${it.path}" }
                    "CDN fetch for $safeUrl returned ${response.status}"
                }
                val bytes = response.bodyAsBytes()
                val md5 = md5Hex(bytes)
                emit(
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

    private fun md5Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        const val CDN_REQUESTS_PER_SECOND: Double = 10.0
    }
}
