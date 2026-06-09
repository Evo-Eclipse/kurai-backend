package com.example.application.acquisition

import com.example.domain.content.md5Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImageObjectKeyTest {
    private val uuidRegex =
        Regex("images/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.[a-z]+")

    @Test
    fun `formats a 32-hex md5 in uuid 8-4-4-4-12 layout`() {
        val md5 = "d41d8cd98f00b204e9800998ecf8427e"
        val key = imageObjectKey(md5, byteArrayOf(0))
        assertTrue(key.startsWith("images/d41d8cd9-8f00-b204-e980-0998ecf8427e."), key)
        assertTrue(uuidRegex.matches(key), key)
    }

    @Test
    fun `detects common image extensions from magic bytes`() {
        val md5 = "00000000000000000000000000000000"
        assertEquals(
            "jpg",
            imageObjectKey(md5, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())).substringAfterLast('.'),
        )
        assertEquals(
            "png",
            imageObjectKey(md5, byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()))
                .substringAfterLast('.'),
        )
        assertEquals("gif", imageObjectKey(md5, "GIF89a".toByteArray()).substringAfterLast('.'))
        assertEquals("bin", imageObjectKey(md5, byteArrayOf(1, 2, 3, 4)).substringAfterLast('.'))
    }

    @Test
    fun `falls back to the raw hash when it is not 32 hex chars`() {
        val key = imageObjectKey("short", byteArrayOf(1))
        assertEquals("images/short.bin", key)
    }

    @Test
    fun `md5Hex matches a known empty-input digest`() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", md5Hex(ByteArray(0)))
    }
}
