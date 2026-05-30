package com.example.infrastructure.content

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class RateLimiterTest {
    @Test
    fun `acquire spaces requests by interval using injected clock`() {
        runBlocking {
            // Virtual clock: starts at 1000ms, never advances on its own;
            // the limiter's "sleep" advances it instead so we can assert
            // exact spacing without wall-clock flakiness.
            var now = 1000L
            val sleepCalls = mutableListOf<Long>()
            val limiter =
                RateLimiter(
                    requestsPerSecond = 2.0,
                    now = { now },
                    sleep = { wait ->
                        sleepCalls += wait
                        now += wait
                    },
                )

            limiter.acquire() // first call, no wait expected
            limiter.acquire() // second, must wait the 500ms interval
            limiter.acquire() // third, must wait again

            assertEquals(listOf(500L, 500L), sleepCalls)
        }
    }

    @Test
    fun `acquire does not sleep when caller is already past next slot`() {
        runBlocking {
            var now = 1000L
            val sleepCalls = mutableListOf<Long>()
            val limiter =
                RateLimiter(
                    requestsPerSecond = 2.0,
                    now = { now },
                    sleep = { wait ->
                        sleepCalls += wait
                        now += wait
                    },
                )
            limiter.acquire() // first
            now += 1000L // caller dawdles for 1s — we are past the next slot
            limiter.acquire() // should not sleep
            assertEquals(emptyList(), sleepCalls)
        }
    }
}
