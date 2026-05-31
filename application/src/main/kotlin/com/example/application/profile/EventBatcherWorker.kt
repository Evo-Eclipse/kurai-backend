package com.example.application.profile

import com.example.infrastructure.sqlite.EventBatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class EventBatcherWorker(
    private val batcher: EventBatcher,
) {
    suspend fun run() {
        try {
            batcher.runFlushLoop()
        } finally {
            // NonCancellable ensures drainAndFlush completes even when the coroutine is being cancelled.
            withContext(NonCancellable) {
                batcher.drainAndFlush()
            }
        }
    }
}
