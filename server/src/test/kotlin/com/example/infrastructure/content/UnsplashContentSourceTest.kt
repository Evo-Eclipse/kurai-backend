package com.example.infrastructure.content

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class UnsplashContentSourceTest {
    private val config =
        UnsplashConfig(
            baseUrl = "https://example.test",
            userAgent = "Kurai/1.0 (test)",
            accessKey = "client-id-key",
        )

    @Test
    fun `fetch yields metadata results downloaded in parallel from CDN`() {
        runBlocking {
            // Real Unsplash response: 30 results, full shape (likes, user,
            // breadcrumbs, blur_hash, …). We rely on `ignoreUnknownKeys`
            // here — adding parsing for new fields later must not break.
            val client =
                mockClient { request ->
                    when {
                        request.url.encodedPath == "/search/photos" ->
                            respondJson(loadResource("/fixtures/unsplash_posts.json"))
                        request.url.host == "images.unsplash.com" ->
                            respond(ByteReadChannel(byteArrayOf(0x12, 0x34)), HttpStatusCode.OK)
                        else -> respondError(HttpStatusCode.NotFound)
                    }
                }
            val source = UnsplashContentSource(config, client, noOpRateLimiter())

            val results = source.fetch(SourceQuery(tags = listOf("apple"), limit = 3)).toList()

            assertEquals(3, results.size)
            results.forEach { raw ->
                assertEquals(Platform("unsplash"), raw.platform)
                assertEquals(32, raw.md5.length, "MD5 should be 32 hex chars")
                assertTrue(raw.cdnUrl.startsWith("https://images.unsplash.com/"))
                assertTrue(raw.originPostUrl.startsWith("https://unsplash.com/photos/"))
            }
        }
    }

    @Test
    fun `fetch sends configured User-Agent and Client-ID auth on API requests`() {
        runBlocking {
            var captured: HttpRequestData? = null
            val client =
                mockClient { request ->
                    if (captured == null) captured = request
                    respondJson("""{"total": 0, "total_pages": 0, "results": []}""")
                }
            val source = UnsplashContentSource(config, client, noOpRateLimiter())
            source.fetch(SourceQuery(tags = listOf("anything"), limit = 1)).toList()

            val req = checkNotNull(captured)
            assertEquals(config.userAgent, req.headers[HttpHeaders.UserAgent])
            assertEquals("Client-ID client-id-key", req.headers[HttpHeaders.Authorization])
        }
    }

    @Test
    fun `CDN 404 on one image is skipped, other results still come through`() {
        runBlocking {
            val client =
                mockClient { request ->
                    val full = request.url.toString()
                    when {
                        request.url.encodedPath == "/search/photos" ->
                            respondJson(loadResource("/fixtures/unsplash_posts.json"))
                        request.url.host == "images.unsplash.com" ->
                            if (full.contains("photo-1630563451961")) {
                                respondError(HttpStatusCode.NotFound)
                            } else {
                                respond(ByteReadChannel(byteArrayOf(0x09)), HttpStatusCode.OK)
                            }
                        else -> respondError(HttpStatusCode.NotFound)
                    }
                }
            val source = UnsplashContentSource(config, client, noOpRateLimiter())

            val results = source.fetch(SourceQuery(tags = listOf("x"), limit = 3)).toList()

            // The 404'd result is silently dropped; flatMapMerge still
            // delivers the requested limit from the remaining hits.
            assertEquals(3, results.size)
            assertTrue(
                results.none { it.sourceId == "zLCR7RsxYGs" },
                "Failed CDN id should be absent: ${results.map { it.sourceId }}",
            )
        }
    }

    @Test
    fun `403 on search means demo-tier or invalid key, fail with clear error`() {
        runBlocking {
            val client = mockClient { _ -> respondError(HttpStatusCode.Forbidden) }
            val source = UnsplashContentSource(config, client, noOpRateLimiter())

            val error =
                assertFailsWith<IllegalStateException> {
                    source.fetch(SourceQuery(tags = listOf("x"), limit = 1)).toList()
                }
            assertTrue(error.message!!.contains("demo-tier"))
        }
    }

    private fun mockClient(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> io.ktor.client.request.HttpResponseData,
    ): HttpClient =
        HttpClient(MockEngine { request -> handler(request) }) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }

    private fun MockRequestHandleScope.respondJson(body: String) =
        respond(
            content = body,
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )

    private fun loadResource(path: String): String =
        UnsplashContentSourceTest::class.java
            .getResourceAsStream(path)
            ?.bufferedReader()
            ?.readText()
            ?: error("Test resource not found: $path")

    private fun noOpRateLimiter() =
        RateLimiter(
            requestsPerSecond = 1000.0,
            now = { 0L },
            sleep = { },
        )
}
