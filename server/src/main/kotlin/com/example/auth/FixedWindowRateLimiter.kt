package com.example.auth

import com.github.benmanes.caffeine.cache.Caffeine
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-memory fixed-window rate limiter keyed by an arbitrary string (here,
 * the client IP). Each key gets [maxPerWindow] permits per [window]: the
 * window opens on the first request and the counter is evicted [window]
 * after that first write, so the next request opens a fresh window.
 *
 * Backed by Caffeine for bounded memory and automatic expiry. Approximate
 * by design — it caps abuse of the unauthenticated key-issuance endpoint,
 * it is not a billing-grade quota. Process-local: each instance counts only
 * its own node, which is fine for the single-process expo deployment.
 */
class FixedWindowRateLimiter(
    private val maxPerWindow: Int,
    window: Duration,
    maxKeys: Long = 100_000,
) {
    /** Hint for a `Retry-After` header — the full window has to elapse. */
    val retryAfterSeconds: Long = window.seconds.coerceAtLeast(1)

    private val counters =
        Caffeine
            .newBuilder()
            .expireAfterWrite(window)
            .maximumSize(maxKeys)
            .build<String, AtomicInteger>()

    /** True while the key is within budget; false once the window is spent. */
    fun tryAcquire(key: String): Boolean = counters.get(key) { AtomicInteger(0) }.incrementAndGet() <= maxPerWindow
}

/** Wrapper so Ktor DI can bind a separate limiter for `/auth/challenge`. */
class ChallengeIpRateLimiter(
    private val delegate: FixedWindowRateLimiter,
) {
    fun tryAcquire(key: String): Boolean = delegate.tryAcquire(key)

    fun retryAfterSeconds(): Long = delegate.retryAfterSeconds
}