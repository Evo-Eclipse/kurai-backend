package com.example.application.profile

import com.example.domain.profile.PendingUserEvent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

/**
 * Buffers [PendingUserEvent] items in-memory and flushes them in batches.
 *
 * Flush is triggered by whichever condition is met first:
 *   - buffer reaches [flushSize] items (size-based)
 *   - [flushTimeoutMs] milliseconds elapse without a size flush (time-based)
 */
class EventBatcher(
    private val flush: suspend (List<PendingUserEvent>) -> Unit,
    private val flushSize: Int = FLUSH_SIZE,
    private val flushTimeoutMs: Long = FLUSH_TIMEOUT_MS,
) {
    private val channel = Channel<PendingUserEvent>(capacity = Channel.UNLIMITED)

    private val buffer = mutableListOf<PendingUserEvent>()

    suspend fun enqueue(data: PendingUserEvent) = channel.send(data)

    suspend fun runFlushLoop() =
        coroutineScope {
            buffer.clear()
            val timeoutChannel = Channel<Unit>(capacity = Channel.CONFLATED)

            fun launchTimeout() =
                launch {
                    delay(flushTimeoutMs)
                    timeoutChannel.trySend(Unit)
                }

            var timeoutJob = launchTimeout()

            try {
                while (true) {
                    select {
                        channel.onReceive { data ->
                            buffer.add(data)
                            if (buffer.size >= flushSize) {
                                timeoutJob.cancel()
                                flush(buffer.toList())
                                buffer.clear()
                                timeoutJob = launchTimeout()
                            }
                        }
                        timeoutChannel.onReceive {
                            if (buffer.isNotEmpty()) {
                                flush(buffer.toList())
                                buffer.clear()
                            }
                            timeoutJob = launchTimeout()
                        }
                    }
                }
            } finally {
                timeoutJob.cancel()
            }
        }

    suspend fun drainAndFlush() {
        while (true) {
            buffer.add(channel.tryReceive().getOrNull() ?: break)
        }
        if (buffer.isNotEmpty()) {
            flush(buffer.toList())
            buffer.clear()
        }
    }

    companion object {
        const val FLUSH_SIZE = 500
        const val FLUSH_TIMEOUT_MS = 500L
    }
}
