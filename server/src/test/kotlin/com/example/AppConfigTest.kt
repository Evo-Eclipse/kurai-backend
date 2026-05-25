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
                ),
            )
        assertEquals("super-secret", config.jwtSecret)
        assertEquals(Path.of("/tmp/kurai-lucene"), config.luceneDir)
        assertEquals(AppConfig.DEFAULT_LUCENE_DEPRECATED_GC_SECONDS, config.luceneDeprecatedGcSeconds)
    }

    @Test
    fun `load fails with clear message when KURAI_JWT_SECRET is missing`() {
        val error = assertFailsWith<IllegalStateException> { AppConfig.load(emptyMap()) }
        assertTrue(error.message!!.contains("KURAI_JWT_SECRET"))
    }
}
