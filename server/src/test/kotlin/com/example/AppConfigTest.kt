package com.example

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AppConfigTest {
    @Test
    fun `load succeeds with required env`() {
        val config =
            AppConfig.load(
                mapOf(
                    "KURAI_JWT_SECRET" to "super-secret",
                    "KURAI_LUCENE_DIR" to "/tmp/kurai-lucene",
                    "KURAI_UNSPLASH_USER_AGENT" to "Kurai/1.0",
                    "KURAI_UNSPLASH_ACCESS_KEY" to "unsplash-key",
                    "KURAI_E621_USER_AGENT" to "Kurai/1.0",
                    "KURAI_E621_USERNAME" to "user",
                    "KURAI_E621_ACCESS_KEY" to "e621-key",
                ),
            )
        assertEquals("super-secret", config.jwtSecret)
        assertEquals(Path.of("/tmp/kurai-lucene"), config.luceneDir)
        assertEquals(AppConfig.DEFAULT_LUCENE_DEPRECATED_GC_SECONDS, config.luceneDeprecatedGcSeconds)
        assertEquals(AppConfig.DEFAULT_ONNX_INTRA_OP_THREADS, config.onnxIntraOpThreads)
    }

    @Test
    fun `load fails with clear message when KURAI_JWT_SECRET is missing`() {
        val error = assertFailsWith<IllegalStateException> { AppConfig.load(emptyMap()) }
        assertTrue(error.message!!.contains("KURAI_JWT_SECRET"))
    }
}
