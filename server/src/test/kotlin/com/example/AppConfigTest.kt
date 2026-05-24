package com.example

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AppConfigTest {
    @Test
    fun `load succeeds when KURAI_JWT_SECRET is present`() {
        val config = AppConfig.load(mapOf("KURAI_JWT_SECRET" to "super-secret"))
        assertEquals("super-secret", config.jwtSecret)
    }

    @Test
    fun `load fails with clear message when KURAI_JWT_SECRET is missing`() {
        val error = assertFailsWith<IllegalStateException> { AppConfig.load(emptyMap()) }
        assertTrue(error.message!!.contains("KURAI_JWT_SECRET"))
    }
}
