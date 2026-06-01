package com.example.application.auth

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Cryptographic primitives shared by [AuthService] and friends. Kept
 * together so any future swap (Argon2, KDF, alternate token length) has
 * one obvious site to edit, and so tests can substitute deterministic
 * variants via constructor injection.
 */
internal object AuthCodecs {
    private val rng = SecureRandom()

    /** SHA-256(input) as 64-char lowercase hex. */
    fun sha256Hex(input: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(input.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /**
     * Constant-time comparison of two hex digests. Both arguments are
     * SHA-256 hex (always 64 chars), so [MessageDigest.isEqual] runs in
     * time independent of where the first mismatch falls — no early-exit
     * timing signal on code/token checks.
     */
    fun constantTimeEquals(
        a: String,
        b: String,
    ): Boolean =
        MessageDigest.isEqual(
            a.toByteArray(StandardCharsets.UTF_8),
            b.toByteArray(StandardCharsets.UTF_8),
        )

    /** 6-digit numeric OTP, zero-padded. */
    fun generateOtp(): String = "%06d".format(rng.nextInt(1_000_000))

    /**
     * 32-byte random refresh token, returned as 64-char lowercase hex.
     * Stored as SHA-256(refreshToken) in `auth_sessions.refresh_hash`,
     * so the raw token never lands in the database.
     */
    fun generateRefreshToken(): String {
        val bytes = ByteArray(32)
        rng.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
