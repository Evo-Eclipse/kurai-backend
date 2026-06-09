package com.example.infrastructure.content

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Literal-IP cases so the guard is exercised without any DNS lookup: the JDK
 * resolves a numeric host to itself, keeping the test hermetic and offline.
 */
class UrlSafetyTest {
    @Test
    fun `accepts a public https URL`() {
        val uri = UrlSafety.requirePublicHttps("https://1.1.1.1/image.jpg")
        assertEquals("1.1.1.1", uri.host)
    }

    @Test
    fun `rejects non-https schemes`() {
        assertFailsWith<IllegalArgumentException> { UrlSafety.requirePublicHttps("http://1.1.1.1/x") }
        assertFailsWith<IllegalArgumentException> { UrlSafety.requirePublicHttps("file:///etc/passwd") }
    }

    @Test
    fun `rejects the cloud-metadata endpoint`() {
        assertFailsWith<IllegalArgumentException> {
            UrlSafety.requirePublicHttps(
                "https://169.254.169.254/latest/meta-data/",
            )
        }
    }

    @Test
    fun `rejects loopback, private and wildcard targets`() {
        listOf(
            "https://127.0.0.1/x",
            "https://[::1]/x",
            "https://10.0.0.1/x",
            "https://192.168.1.1/x",
            "https://172.16.0.1/x",
            "https://0.0.0.0/x",
        ).forEach { url ->
            assertFailsWith<IllegalArgumentException>("expected reject for $url") {
                UrlSafety.requirePublicHttps(url)
            }
        }
    }

    @Test
    fun `rejects an ipv6 unique-local target`() {
        assertFailsWith<IllegalArgumentException> { UrlSafety.requirePublicHttps("https://[fd00::1]/x") }
    }

    @Test
    fun `rejects a malformed url`() {
        assertFailsWith<IllegalArgumentException> { UrlSafety.requirePublicHttps("not a url") }
    }
}
