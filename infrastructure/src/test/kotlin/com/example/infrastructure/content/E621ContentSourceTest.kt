package com.example.infrastructure.content

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
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
import kotlin.test.assertTrue

class E621ContentSourceTest {
    private val config =
        E621Config(
            baseUrl = "https://example.test",
            userAgent = "Kurai/1.0 (test)",
            username = "tester",
            accessKey = "client-id-key",
        )

    @Test
    fun `fetch yields only still-image posts and stops at requested limit`() {
        runBlocking {
            // Real fixture: 20 posts (9 png + 5 jpg + 5 gif + 1 webm).
            // Only the 1 webm must be silently skipped; we ask for 3 and
            // expect the first 3 image posts (in fixture order) back.
            val sent = mutableListOf<String>()
            val client =
                mockClient { request ->
                    sent += request.url.encodedPath
                    when {
                        request.url.encodedPath == "/posts.json" &&
                            request.url.parameters["page"] == "1" ->
                            respondJson(loadResource("/fixtures/e621_posts.json"))
                        request.url.encodedPath == "/posts.json" ->
                            respondJson("""{"posts": []}""")
                        else -> respond(ByteReadChannel(byteArrayOf(0x42)), HttpStatusCode.OK)
                    }
                }
            val source = E621ContentSource(config, client, noOpRateLimiter())

            val results = source.fetch(SourceQuery(tags = listOf("anything"), limit = 3)).toList()

            assertEquals(3, results.size)
            results.forEach { raw ->
                assertEquals(Platform("e621"), raw.platform)
                assertTrue(
                    raw.cdnUrl.endsWith(".jpg") || raw.cdnUrl.endsWith(".jpeg") ||
                        raw.cdnUrl.endsWith(".png") || raw.cdnUrl.endsWith(".webp") ||
                        raw.cdnUrl.endsWith(".gif"),
                    "Expected image ext in ${raw.cdnUrl}",
                )
                assertTrue(raw.originPostUrl.startsWith("https://example.test/posts/"))
                assertTrue(raw.md5.length == 32, "MD5 should be 32 hex chars")
            }
            // One API call was enough — pagination did not advance.
            assertEquals(1, sent.count { it == "/posts.json" })
        }
    }

    @Test
    fun `fetch sends configured User-Agent and HTTP Basic auth on API requests`() {
        runBlocking {
            var captured: HttpRequestData? = null
            val client =
                mockClient { request ->
                    if (captured == null) captured = request
                    respondJson("""{"posts": []}""")
                }
            val source = E621ContentSource(config, client, noOpRateLimiter())
            source.fetch(SourceQuery(tags = listOf("anything"), limit = 1)).toList()

            val req = checkNotNull(captured)
            assertEquals(config.userAgent, req.headers[HttpHeaders.UserAgent])
            // base64("tester:client-id-key") = dGVzdGVyOmNsaWVudC1pZC1rZXk=
            assertEquals("Basic dGVzdGVyOmNsaWVudC1pZC1rZXk=", req.headers[HttpHeaders.Authorization])
        }
    }

    @Test
    fun `fetch skips posts with no file URL (deleted or restricted)`() {
        runBlocking {
            val twoPostsOneMissingUrl =
                """
                {
                  "posts": [
                    {"id": 9001, "file": {"md5": "00000000000000000000000000000000", "ext": "jpg"}, "rating": "s"},
                    {"id": 9002,
                     "file": {"md5": "11111111111111111111111111111111", "ext": "jpg",
                              "url": "https://example.test/data/ok.jpg"},
                     "rating": "s"}
                  ]
                }
                """.trimIndent()
            val client =
                mockClient { request ->
                    when (request.url.encodedPath) {
                        "/posts.json" if request.url.parameters["page"] == "1"
                        -> {
                            respondJson(twoPostsOneMissingUrl)
                        }
                        "/posts.json" -> {
                            respondJson("""{"posts": []}""")
                        }
                        else -> {
                            respond(ByteReadChannel(byteArrayOf(0x77)), HttpStatusCode.OK)
                        }
                    }
                }
            val source = E621ContentSource(config, client, noOpRateLimiter())
            val results = source.fetch(SourceQuery(tags = listOf("foo"), limit = 1)).toList()
            assertEquals(listOf("9002"), results.map { it.sourceId })
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
        E621ContentSourceTest::class.java
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
