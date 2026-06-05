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
                    "KURAI_SQLITE_PATH" to "/tmp/kurai.db",
                    "KURAI_OBJECT_STORE_DIR" to "/tmp/kurai-objects",
                    "KURAI_ONNX_MODEL_PATH" to "/tmp/model.onnx",
                    "KURAI_ONNX_MODEL_SHA256" to "abc123",
                ),
            )
        assertEquals("super-secret", config.jwtSecret)
        assertEquals(Path.of("/tmp/kurai-lucene"), config.luceneDir)
        assertEquals(AppConfig.DEFAULT_ONNX_INTRA_OP_THREADS, config.onnxIntraOpThreads)
        assertEquals(
            AppConfig.DEFAULT_ONNX_INFERENCE_PARALLELISM,
            config.onnxInferenceParallelism,
        )
    }

    @Test
    fun `load reads ONNX tuning env overrides`() {
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
                    "KURAI_SQLITE_PATH" to "/tmp/kurai.db",
                    "KURAI_OBJECT_STORE_DIR" to "/tmp/kurai-objects",
                    "KURAI_ONNX_MODEL_PATH" to "/tmp/model.onnx",
                    "KURAI_ONNX_MODEL_SHA256" to "abc123",
                    "KURAI_ONNX_INTRA_OP_THREADS" to "4",
                    "KURAI_ONNX_INFERENCE_PARALLELISM" to "1",
                ),
            )
        assertEquals(4, config.onnxIntraOpThreads)
        assertEquals(1, config.onnxInferenceParallelism)
    }

    @Test
    fun `load fails with clear message when KURAI_JWT_SECRET is missing`() {
        val error = assertFailsWith<IllegalStateException> { AppConfig.load(emptyMap()) }
        assertTrue(error.message!!.contains("KURAI_JWT_SECRET"))
    }

    @Test
    fun `mail-stub gate is off unless KURAI_AUTH_MAIL_STUB is truthy`() {
        assertEquals(false, AppConfig.load(requiredEnv()).authMailStub)
        assertEquals(true, AppConfig.load(requiredEnv() + ("KURAI_AUTH_MAIL_STUB" to "true")).authMailStub)
        assertEquals(false, AppConfig.load(requiredEnv() + ("KURAI_AUTH_MAIL_STUB" to "false")).authMailStub)
    }

    private fun requiredEnv() =
        mapOf(
            "KURAI_JWT_SECRET" to "super-secret",
            "KURAI_LUCENE_DIR" to "/tmp/kurai-lucene",
            "KURAI_UNSPLASH_USER_AGENT" to "Kurai/1.0",
            "KURAI_UNSPLASH_ACCESS_KEY" to "unsplash-key",
            "KURAI_E621_USER_AGENT" to "Kurai/1.0",
            "KURAI_E621_USERNAME" to "user",
            "KURAI_E621_ACCESS_KEY" to "e621-key",
            "KURAI_SQLITE_PATH" to "/tmp/kurai.db",
            "KURAI_OBJECT_STORE_DIR" to "/tmp/kurai-objects",
            "KURAI_ONNX_MODEL_PATH" to "/tmp/model.onnx",
            "KURAI_ONNX_MODEL_SHA256" to "abc123",
        )
}
