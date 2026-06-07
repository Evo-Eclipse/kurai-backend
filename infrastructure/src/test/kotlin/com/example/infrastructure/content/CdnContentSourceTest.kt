package com.example.infrastructure.content
import com.example.domain.content.Platform
import com.example.domain.content.RawImage
import com.example.domain.content.SourceQuery
import com.example.domain.content.md5Hex
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CdnContentSourceTest {
    private fun mockClient(vararg payloads: ByteArray): HttpClient {
        val iter = payloads.iterator()
        return HttpClient(
            MockEngine {
                if (iter.hasNext()) {
                    respond(ByteReadChannel(iter.next()), HttpStatusCode.OK)
                } else {
                    respondError(HttpStatusCode.NotFound)
                }
            },
        )
    }

    private fun noOpRateLimiter() = RateLimiter(requestsPerSecond = 1000.0, now = { 0L }, sleep = {})

    @Test
    fun `fetch emits one RawImage per URL with correct MD5`() {
        runBlocking {
            val bytes1 = byteArrayOf(0x11, 0x22)
            val bytes2 = byteArrayOf(0x33, 0x44, 0x55)
            val client = mockClient(bytes1, bytes2)
            val source = CdnContentSource(client, noOpRateLimiter())

            val urls = listOf("https://cdn.example/a.jpg", "https://cdn.example/b.jpg")
            val results = mutableListOf<RawImage>()
            source.fetch(SourceQuery(tags = urls, limit = 2)) { results += it }

            assertEquals(2, results.size)
            results.forEach { assertEquals(Platform("cdn"), it.platform) }
            assertEquals(md5Hex(bytes1), results[0].md5)
            assertEquals(md5Hex(bytes2), results[1].md5)
            assertEquals(urls[0], results[0].sourceId)
            assertEquals(urls[1], results[1].sourceId)
        }
    }

    @Test
    fun `search maps URLs to ContentItems without downloading`() {
        runBlocking {
            // Any HTTP call would fail — search must stay download-free.
            val client = HttpClient(MockEngine { respondError(HttpStatusCode.InternalServerError) })
            val source = CdnContentSource(client, noOpRateLimiter())
            val urls = listOf("https://cdn.example/a.jpg", "https://cdn.example/b.jpg", "https://cdn.example/c.jpg")

            val items = source.search(SourceQuery(tags = urls, limit = 2))

            assertEquals(2, items.size)
            assertEquals(urls[0], items[0].cdnUrl)
            assertEquals(Platform("cdn"), items[0].platform)
        }
    }

    @Test
    fun `fetch respects limit even when tags list is larger`() {
        runBlocking {
            val client = mockClient(byteArrayOf(0x01), byteArrayOf(0x02), byteArrayOf(0x03))
            val source = CdnContentSource(client, noOpRateLimiter())
            val urls = listOf("https://cdn.example/1.jpg", "https://cdn.example/2.jpg", "https://cdn.example/3.jpg")

            val results = mutableListOf<RawImage>()
            source.fetch(SourceQuery(tags = urls, limit = 2)) { results += it }

            assertEquals(2, results.size)
        }
    }

    @Test
    fun `fetch throws on non-200 response`() {
        runBlocking {
            val client = HttpClient(MockEngine { respondError(HttpStatusCode.NotFound) })
            val source = CdnContentSource(client, noOpRateLimiter())

            assertFailsWith<IllegalStateException> {
                val results = mutableListOf<RawImage>()
                source.fetch(SourceQuery(tags = listOf("https://cdn.example/gone.jpg"), limit = 1)) { results += it }
            }
        }
    }

    @Test
    fun `fetch acquires rate limiter once per URL`() {
        runBlocking {
            val n = 11
            var virtualNow = 0L
            val sleepDurations = mutableListOf<Long>()
            val rateLimiter =
                RateLimiter(
                    requestsPerSecond = CdnContentSource.CDN_REQUESTS_PER_SECOND,
                    now = { virtualNow },
                    sleep = { ms ->
                        sleepDurations += ms
                        virtualNow += ms
                    },
                )
            val bytes = byteArrayOf(0xAB.toByte())
            val client = HttpClient(MockEngine { respond(ByteReadChannel(bytes), HttpStatusCode.OK) })
            val source = CdnContentSource(client, rateLimiter)
            val urls = (1..n).map { "https://cdn.example/$it.jpg" }

            val results = mutableListOf<RawImage>()
            source.fetch(SourceQuery(tags = urls, limit = n)) { results += it }

            // n requests at 10 req/s: (n-1) intervals of 100ms each
            val expectedIntervalMs = (1000.0 / CdnContentSource.CDN_REQUESTS_PER_SECOND).toLong()
            assertEquals(n - 1, sleepDurations.size, "Expected ${n - 1} rate-limiter sleeps for $n requests")
            assertTrue(
                sleepDurations.sum() >= expectedIntervalMs * (n - 1),
                "Total sleep ${sleepDurations.sum()}ms should be >= ${expectedIntervalMs * (n - 1)}ms",
            )
        }
    }
}
