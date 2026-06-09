package com.example.domain.content

import java.security.MessageDigest

/** Lowercase-hex MD5 of [bytes]; matches the content-source dedup hash. */
fun md5Hex(bytes: ByteArray): String =
    MessageDigest
        .getInstance("MD5")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
