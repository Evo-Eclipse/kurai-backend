package com.example.infrastructure.sqlite

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

/**
 * Buffers [EventData] items in-memory and flushes them in batches.
 *
 * Flush is triggered by whichever condition is met first:
 *   - buffer reaches [flushSize] items (size-based)
 *   - [flushTimeoutMs] milliseconds elapse without a size flush (time-based)
 *
 * [EventBatcher] itself has no domain imports — it works entirely with
 * [EventData] (infrastructure DTO). Application.kt bridges it to the
 * domain-layer [com.example.domain.events.EventQueue] interface via a
 * lambda capture.
 */
class EventBatcher(
    private val flush: suspend (List<EventData>) -> Unit,
    private val flushSize: Int = FLUSH_SIZE,
    private val flushTimeoutMs: Long = FLUSH_TIMEOUT_MS,
) {
    private val channel = Channel<EventData>(capacity = Channel.UNLIMITED)

    // Promoted to field so drainAndFlush can recover items moved here before cancellation.
    private val buffer = mutableListOf<EventData>()

    suspend fun enqueue(data: EventData) = channel.send(data)

    /**
     * Runs the flush loop until the coroutine is cancelled.
     * Call this from [com.example.workers.EventBatcherWorker].
     */
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

    /**
     * Flushes any items remaining in the in-memory buffer or channel. Call after cancellation.
     * PRECONDITION: runFlushLoop's coroutineScope must have fully completed before calling this;
     * buffer access is not synchronized and concurrent access is a data race.
     */
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
