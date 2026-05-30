package com.example.infrastructure.content

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.basicAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.appendPathSegments
import io.ktor.http.takeFrom
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

/**
 * `ContentSource` adapter for e621.
 *
 * Search uses `/posts.json?tags=...&limit=...&page=N` with HTTP Basic auth
 * (`username:api_key`) and a descriptive `User-Agent` — both required by
 * e621's API policy. Pagination walks integer pages until the page is
 * empty or the caller-requested `limit` is satisfied. Image bytes are
 * fetched directly from `file.url`.
 */
class E621ContentSource(
    private val config: E621Config,
    private val httpClient: HttpClient,
    private val apiRateLimiter: RateLimiter = RateLimiter(API_REQUESTS_PER_SECOND),
    private val cdnRateLimiter: RateLimiter = RateLimiter(CDN_REQUESTS_PER_SECOND),
    private val cdnConcurrency: Int = CDN_CONCURRENCY,
) : ContentSource {
    private val log = LoggerFactory.getLogger(E621ContentSource::class.java)

    override val platform: Platform = Platform("e621")

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override fun fetch(query: SourceQuery): Flow<RawImage> =
        flow {
            val pageSize = minOf(query.limit, PER_PAGE)
            var page = 1
            var emitted = 0
            while (emitted < query.limit) {
                val posts = fetchPage(query.tags, pageSize, page)
                if (posts.isEmpty()) break
                val downloads =
                    posts
                        .asFlow()
                        .flatMapMerge(cdnConcurrency) { post ->
                            val raw = downloadOrNull(post)
                            if (raw == null) flowOf() else flowOf(raw)
                        }
                downloads.take(query.limit - emitted).collect { raw ->
                    emit(raw)
                    emitted += 1
                }
                if (emitted >= query.limit) return@flow
                page += 1
            }
        }

    private suspend fun fetchPage(
        tags: List<String>,
        limit: Int,
        page: Int,
    ): List<Post> {
        apiRateLimiter.acquire()
        val response: HttpResponse =
            httpClient.get {
                url {
                    takeFrom(config.baseUrl)
                    appendPathSegments("posts.json")
                }
                parameter("tags", tags.joinToString(" "))
                parameter("limit", limit)
                parameter("page", page)
                header(HttpHeaders.UserAgent, config.userAgent)
                basicAuth(config.username, config.accessKey)
            }
        val body: PostsResponse = response.body()
        return body.posts
    }

    private suspend fun downloadOrNull(post: Post): RawImage? {
        if (post.file.ext !in IMAGE_EXTENSIONS) {
            log.info("e621 post id={} skipped: ext={} is not an image", post.id, post.file.ext)
            return null
        }
        val url =
            post.file.url ?: return null.also {
                log.info("e621 post id={} skipped: no file URL (likely deleted/restricted)", post.id)
            }
        cdnRateLimiter.acquire()
        val response = httpClient.get(url) { header(HttpHeaders.UserAgent, config.userAgent) }
        if (response.status != HttpStatusCode.OK) {
            log.info("e621 CDN {} returned {}", url, response.status)
            return null
        }
        val bytes = response.bodyAsBytes()
        return RawImage(
            platform = platform,
            sourceId = post.id.toString(),
            originPostUrl = "${config.baseUrl}/posts/${post.id}",
            cdnUrl = url,
            rating = post.rating,
            md5 = post.file.md5,
            bytes = bytes,
        )
    }

    @Serializable
    private data class PostsResponse(
        val posts: List<Post>,
    )

    @Serializable
    private data class Post(
        val id: Long,
        val file: FileMeta,
        val rating: String? = null,
    )

    @Serializable
    private data class FileMeta(
        val md5: String,
        val ext: String,
        @SerialName("url") val url: String? = null,
    )

    companion object {
        /** Documented E621 API policy: 2 req/s. */
        const val API_REQUESTS_PER_SECOND: Double = 2.0
        const val CDN_REQUESTS_PER_SECOND: Double = 10.0
        const val PER_PAGE: Int = 320
        const val CDN_CONCURRENCY: Int = 10

        /** Skip video-only formats; GIF is decoded as its first frame by ImageIO. */
        private val IMAGE_EXTENSIONS: Set<String> = setOf("jpg", "jpeg", "png", "webp", "gif")
    }
}
