package com.example.auth

import com.github.benmanes.caffeine.cache.Caffeine
import java.time.Duration

/**
 * In-memory fixed-window rate limiter keyed by an arbitrary string (here,
 * the client IP). Both the budget [maxPerWindow] and the [windowMs] length
 * are read live on every call, so operator edits in `runtime_config` take
 * effect immediately.
 *
 * The window is tracked explicitly per key (start timestamp + count) rather
 * than via cache expiry, which is what lets the window length change at
 * runtime. Caffeine is used only as a bounded map that evicts idle keys, so
 * memory stays capped. Approximate by design — it caps abuse of the
 * unauthenticated key-issuance endpoint, not a billing-grade quota.
 * Process-local: each instance counts only its own node.
 */
class FixedWindowRateLimiter(
    private val maxPerWindow: suspend () -> Int,
    private val windowMs: suspend () -> Long,
    private val clock: () -> Long = { System.currentTimeMillis() },
    idleEviction: Duration = Duration.ofHours(1),
    maxKeys: Long = 100_000,
) {
    private class Window(
        val start: Long,
        val count: Int,
    )

    private val counters =
        Caffeine
            .newBuilder()
            .expireAfterAccess(idleEviction)
            .maximumSize(maxKeys)
            .build<String, Window>()

    /** Hint for a `Retry-After` header — the full window has to elapse. */
    suspend fun retryAfterSeconds(): Long = (windowMs() / 1000).coerceAtLeast(1)

    /** True while the key is within budget; false once the window is spent. */
    suspend fun tryAcquire(key: String): Boolean {
        val now = clock()
        val window = windowMs()
        val updated =
            checkNotNull(
                counters.asMap().compute(key) { _, current ->
                    if (current == null || now - current.start >= window) {
                        Window(start = now, count = 1)
                    } else {
                        Window(start = current.start, count = current.count + 1)
                    }
                },
            )
        return updated.count <= maxPerWindow()
    }
}

/** Wrapper so Ktor DI can bind a separate limiter for `/auth/challenge`. */
class ChallengeIpRateLimiter(
    private val delegate: FixedWindowRateLimiter,
) {
    suspend fun tryAcquire(key: String): Boolean = delegate.tryAcquire(key)

    suspend fun retryAfterSeconds(): Long = delegate.retryAfterSeconds()
}
