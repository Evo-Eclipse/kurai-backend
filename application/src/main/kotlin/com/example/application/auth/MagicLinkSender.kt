package com.example.application.auth

import org.slf4j.LoggerFactory

/**
 * Out-of-band delivery channel for the magic-link / OTP code. The
 * client receives both forms in one delivery:
 *  - a URL like `https://kurai.app/auth?challenge=<id>&code=<otp>`
 *    that pre-fills the verify form (magic-link mode);
 *  - the 6-digit `otp` itself, so the user can type it manually if the
 *    link cannot be opened (OTP fallback).
 *
 * The challengeId, code, and email arrive raw — the sender chooses how
 * to render them. Real implementations live in `:infrastructure/email/`
 * (next wave).
 */
interface MagicLinkSender {
    suspend fun send(
        email: String,
        challengeId: String,
        code: String,
    )
}

/**
 * Default sender used when no real SMTP/Resend provider is wired.
 * Writes the verification material to the application log at INFO so
 * developers can copy it during local runs and integration tests. Not
 * for production traffic — see the AppConfig stub note.
 */
class LoggingMagicLinkSender : MagicLinkSender {
    private val log = LoggerFactory.getLogger(LoggingMagicLinkSender::class.java)

    override suspend fun send(
        email: String,
        challengeId: String,
        code: String,
    ) {
        log.info("[STUB-MAIL] to={} challengeId={} code={}", email, challengeId, code)
    }
}
