package com.example.infrastructure.content
import com.example.domain.content.ContentSource
import com.example.domain.content.Platform
import com.example.domain.content.RawImage
import com.example.domain.content.SourceQuery
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.appendPathSegments
import io.ktor.http.takeFrom
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.security.MessageDigest

/**
 * `ContentSource` adapter for Unsplash.
 *
 * Two-phase by design (per `design.md` infrastructure-content-unsplash):
 *  1. **API phase** — `/search/photos?query=...&page=N&per_page=30`
 *     Production-tier limit is 5000 req/h ≈ [API_REQUESTS_PER_SECOND];
 *     each call yields up to 30 metadata records.
 *  2. **CDN phase** — `flatMapMerge(8)` over the returned URLs.
 *     Skip 404s (deleted/private images) without breaking the stream.
 *
 * Authentication is `Authorization: Client-ID <accessKey>`; no Basic
 * auth or per-user OAuth is required for public search.
 */
class UnsplashContentSource(
    private val config: UnsplashConfig,
    private val httpClient: HttpClient,
    private val apiRateLimiter: RateLimiter = RateLimiter(API_REQUESTS_PER_SECOND),
    private val cdnRateLimiter: RateLimiter = RateLimiter(CDN_REQUESTS_PER_SECOND),
    private val cdnConcurrency: Int = CDN_CONCURRENCY,
) : ContentSource {
    private val log = LoggerFactory.getLogger(UnsplashContentSource::class.java)

    override val platform: Platform = Platform("unsplash")

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override suspend fun fetch(
        query: SourceQuery,
        onImage: suspend (RawImage) -> Unit,
    ) {
        flow {
            val q = query.tags.joinToString(" ")
            var page = 1
            var emitted = 0
            while (emitted < query.limit) {
                val results = searchPage(q, page)
                if (results.isEmpty()) break
                val downloads =
                    results
                        .asFlow()
                        .flatMapMerge(cdnConcurrency) { hit ->
                            val raw = downloadOrNull(hit)
                            if (raw == null) flowOf() else flowOf(raw)
                        }
                downloads.take(query.limit - emitted).collect { raw ->
                    emit(raw)
                    emitted += 1
                }
                if (emitted >= query.limit) return@flow
                page += 1
            }
        }.collect(onImage)
    }

    private suspend fun searchPage(
        query: String,
        page: Int,
    ): List<Hit> {
        apiRateLimiter.acquire()
        val response: HttpResponse =
            httpClient.get {
                url {
                    takeFrom(config.baseUrl)
                    appendPathSegments("search", "photos")
                }
                parameter("query", query)
                parameter("page", page)
                parameter("per_page", PER_PAGE)
                header(HttpHeaders.UserAgent, config.userAgent)
                header(HttpHeaders.Authorization, "Client-ID ${config.accessKey}")
            }
        if (response.status == HttpStatusCode.Forbidden) {
            error("Unsplash returned 403 — demo-tier or invalid access key (production tier required)")
        }
        val body: SearchResponse = response.body()
        return body.results
    }

    private suspend fun downloadOrNull(hit: Hit): RawImage? {
        val url = hit.urls.regular
        cdnRateLimiter.acquire()
        val response = httpClient.get(url) { header(HttpHeaders.UserAgent, config.userAgent) }
        if (response.status != HttpStatusCode.OK) {
            log.info("unsplash CDN {} returned {} — skipping", url, response.status)
            return null
        }
        val bytes = response.bodyAsBytes()
        return RawImage(
            platform = platform,
            sourceId = hit.id,
            originPostUrl = hit.links.html,
            cdnUrl = url,
            rating = null, // Unsplash does not expose a content rating.
            md5 = md5Hex(bytes),
            bytes = bytes,
        )
    }

    private fun md5Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

    @Serializable
    private data class SearchResponse(
        val results: List<Hit>,
    )

    @Serializable
    private data class Hit(
        val id: String,
        val urls: Urls,
        val links: Links,
    )

    @Serializable
    private data class Urls(
        val regular: String,
    )

    @Serializable
    private data class Links(
        val html: String,
    )

    companion object {
        /** Free-tier 50 req/h / 3600 = 0.014; set to 1.38 for production tier (5 000 req/h). */
        const val API_REQUESTS_PER_SECOND: Double = 0.014
        const val CDN_REQUESTS_PER_SECOND: Double = 10.0
        const val PER_PAGE: Int = 30
        const val CDN_CONCURRENCY: Int = 10
    }
}
