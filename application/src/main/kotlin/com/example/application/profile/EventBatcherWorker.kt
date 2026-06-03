package com.example.application.profile

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(EventBatcherWorker::class.java)

class EventBatcherWorker(
    private val batcher: EventBatcher,
) {
    suspend fun run() {
        try {
            batcher.runFlushLoop()
        } catch (e: CancellationException) {
            withContext(NonCancellable) { batcher.drainAndFlush() }
            throw e
        } catch (e: Exception) {
            log.error("EventBatcherWorker crashed; worker stopped permanently", e)
        }
    }
}
