package com.example.infrastructure.sqlite

import com.example.workers.EventBatcherWorker
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class EventBatcherTest {
    private fun event(i: Int) = EventData(userId = 1L, itemId = i.toLong(), weight = 1.0f, embeddingVersion = "v1")

    @Test
    fun `size flush fires when buffer reaches flushSize`() =
        runTest {
            val flushed = mutableListOf<EventData>()
            val batcher = EventBatcher(flush = { flushed.addAll(it) }, flushSize = 500)
            val job = launch { batcher.runFlushLoop() }

            repeat(500) { batcher.enqueue(event(it)) }
            // runCurrent processes pending channel receives without advancing virtual time;
            // avoids the infinite reschedule loop that advanceUntilIdle() would trigger.
            runCurrent()

            assertEquals(500, flushed.size)
            job.cancel()
            job.join()
        }

    @Test
    fun `time flush fires after timeout elapses`() =
        runTest {
            val flushed = mutableListOf<EventData>()
            val batcher = EventBatcher(flush = { flushed.addAll(it) }, flushSize = 500, flushTimeoutMs = 500L)
            val job = launch { batcher.runFlushLoop() }

            repeat(10) { batcher.enqueue(event(it)) }
            advanceTimeBy(600L)

            assertEquals(10, flushed.size)
            job.cancel()
            job.join()
        }

    @Test
    fun `worker drainAndFlush delivers remaining items on cancellation`() =
        runTest {
            val flushed = mutableListOf<EventData>()
            val batcher = EventBatcher(flush = { flushed.addAll(it) }, flushSize = 500)
            val worker = EventBatcherWorker(batcher)
            val job = launch { worker.run() }

            repeat(7) { batcher.enqueue(event(it)) }
            // Let the worker start and read items from the channel into the buffer.
            // Without this yield the job would be cancelled before it ever starts,
            // and the finally-block drainAndFlush would never be called.
            yield()
            job.cancel()
            job.join()

            assertEquals(7, flushed.size)
        }

    @Test
    fun `concurrent enqueue loses no events`() =
        runTest {
            val flushed = mutableListOf<EventData>()
            val batcher = EventBatcher(flush = { flushed.addAll(it) }, flushSize = 100)
            val loopJob = launch { batcher.runFlushLoop() }

            val producers =
                (1..100).map { i ->
                    launch {
                        repeat(5) { j ->
                            batcher.enqueue(
                                EventData(
                                    userId = i.toLong(),
                                    itemId = j.toLong(),
                                    weight = 1.0f,
                                    embeddingVersion = "v1",
                                ),
                            )
                        }
                    }
                }
            producers.forEach { it.join() }
            runCurrent()

            assertEquals(500, flushed.size)
            loopJob.cancel()
            loopJob.join()
        }
}
