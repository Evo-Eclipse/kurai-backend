package com.example.application.acquisition

/**
 * Object-store key for an image's bytes: `images/<uuid>.<ext>`.
 *
 * The [md5] (32 lowercase hex chars, the content hash already used for dedup)
 * is rendered in the canonical UUID 8-4-4-4-12 layout for readability, and the
 * extension is sniffed from the [bytes] magic number. Same content -> same key,
 * so puts stay idempotent.
 */
fun imageObjectKey(
    md5: String,
    bytes: ByteArray,
): String = "images/${md5AsUuid(md5)}.${imageExtension(bytes)}"

/**
 * Renders a 32-hex-char MD5 in UUID 8-4-4-4-12 form. Falls back to the raw
 * value if it is not exactly 32 hex chars, so an unexpected hash never breaks
 * key construction.
 */
private fun md5AsUuid(md5: String): String {
    if (md5.length != 32 || !md5.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
        return md5
    }
    return buildString {
        append(md5, 0, 8)
        append('-')
        append(md5, 8, 12)
        append('-')
        append(md5, 12, 16)
        append('-')
        append(md5, 16, 20)
        append('-')
        append(md5, 20, 32)
    }
}

/** Best-effort image extension from the leading magic bytes; `bin` when unknown. */
private fun imageExtension(bytes: ByteArray): String {
    fun at(
        i: Int,
        v: Int,
    ): Boolean = i < bytes.size && bytes[i] == v.toByte()

    fun ascii(
        i: Int,
        c: Char,
    ): Boolean = i < bytes.size && bytes[i] == c.code.toByte()

    return when {
        at(0, 0xFF) && at(1, 0xD8) && at(2, 0xFF) -> "jpg"
        at(0, 0x89) && ascii(1, 'P') && ascii(2, 'N') && ascii(3, 'G') -> "png"
        ascii(0, 'G') && ascii(1, 'I') && ascii(2, 'F') -> "gif"
        ascii(0, 'R') && ascii(1, 'I') && ascii(2, 'F') && ascii(3, 'F') &&
            ascii(8, 'W') && ascii(9, 'E') && ascii(10, 'B') && ascii(11, 'P') -> "webp"
        at(0, 0x42) && at(1, 0x4D) -> "bmp"
        else -> "bin"
    }
}
