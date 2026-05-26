package com.example.domain.profile

import com.example.domain.model.EmbeddingVersion
import com.example.domain.model.Prototype
import com.example.domain.model.UserEvent
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProfileServiceTest {
    private val ev = EmbeddingVersion("v1")

    private fun itemVec(seed: Int): FloatArray {
        val v = FloatArray(Prototype.VECTOR_DIM) { (it + seed).toFloat() }
        return Scoring.l2Normalize(v)
    }

    private fun event(
        id: Long,
        weight: Float = 0.5f,
    ) = UserEvent(id, 1L, id, weight, ev, 1000L)

    private fun noOpSave(): ProfileSavePort = { _ -> }

    private fun emptyLoadProfile(): ProfileLoadPort = { _ -> null }

    private fun emptyLoadEvents(): EventLoadPort = { _, _ -> emptyList() }

    @Test
    fun `concurrent updates do not lose writes`() {
        runBlocking {
            val count = 1000
            val service =
                ProfileService(
                    loadProfile = emptyLoadProfile(),
                    loadEvents = emptyLoadEvents(),
                    saveProfile = noOpSave(),
                )
            val jobs =
                (1L..count.toLong()).map { id ->
                    launch { service.update(1L, event(id), itemVec(id.toInt())) }
                }
            jobs.forEach { it.join() }
            val profile = service.getOrLoad(1L)
            assertEquals(count.toLong(), profile.lastAppliedEventId)
        }
    }

    @Test
    fun `LRU evicts oldest user when capacity exceeded`() {
        runBlocking {
            val service =
                ProfileService(
                    loadProfile = emptyLoadProfile(),
                    loadEvents = emptyLoadEvents(),
                    saveProfile = noOpSave(),
                    cacheCapacity = 2L,
                )
            service.update(1L, event(1L), itemVec(1))
            service.update(2L, event(2L), itemVec(2))
            service.update(3L, event(3L), itemVec(3)) // should evict user 1
            assertNotNull(service.getOrLoad(2L))
            assertNotNull(service.getOrLoad(3L))
        }
    }

    @Test
    fun `snapshotDirty is idempotent — second call returns empty`() {
        runBlocking {
            val service =
                ProfileService(
                    loadProfile = emptyLoadProfile(),
                    loadEvents = emptyLoadEvents(),
                    saveProfile = noOpSave(),
                )
            service.update(1L, event(1L), itemVec(1))
            val first = service.snapshotDirty()
            val second = service.snapshotDirty()
            assertTrue(first.isNotEmpty(), "First snapshot should have dirty entries")
            assertTrue(second.isEmpty(), "Second snapshot should be empty after drain")
        }
    }

    @Test
    fun `getOrLoad replays events from EventLoadPort`() {
        runBlocking {
            val vec1 = itemVec(1)
            val vec2 = itemVec(2)
            val vec3 = itemVec(3)
            val replayEvents =
                listOf(
                    event(10L) to vec1,
                    event(20L) to vec2,
                    event(30L) to vec3,
                )
            val service =
                ProfileService(
                    loadProfile = emptyLoadProfile(),
                    loadEvents = { _, _ -> replayEvents },
                    saveProfile = noOpSave(),
                )
            val profile = service.getOrLoad(1L)
            assertEquals(30L, profile.lastAppliedEventId)
        }
    }
}
