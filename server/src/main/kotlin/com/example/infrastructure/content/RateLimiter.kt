package com.example.infrastructure.content

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Minimal token-bucket rate limiter for outbound HTTP. Permits N requests
 * per second; [acquire] suspends until the next slot is free.
 *
 * Implementation is intentionally trivial (one mutex + one timestamp):
 *   - Sufficient for the single-process acquisition pipeline we run.
 *   - Easy to test deterministically by injecting a fake clock.
 *   - Holds under coroutine cancellation: `delay` is a cancellation point.
 *
 * For multi-host or multi-process deployments swap in a distributed
 * limiter; this seam is local to [ContentSource] adapters and reusable.
 */
class RateLimiter(
    requestsPerSecond: Double,
    private val now: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    init {
        require(requestsPerSecond > 0.0) { "requestsPerSecond must be positive, got $requestsPerSecond" }
    }

    private val intervalMillis: Long = (1000.0 / requestsPerSecond).toLong()
    private val mutex = Mutex()
    private var nextSlotMillis: Long = 0L

    suspend fun acquire() {
        val wait =
            mutex.withLock {
                val now = now()
                val target = maxOf(now, nextSlotMillis)
                nextSlotMillis = target + intervalMillis
                target - now
            }
        if (wait > 0) sleep(wait)
    }
}
