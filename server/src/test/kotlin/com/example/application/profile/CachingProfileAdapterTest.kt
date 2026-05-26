package com.example.application.profile

import com.example.domain.model.EmbeddingVersion
import com.example.domain.model.Prototype
import com.example.domain.model.UserEvent
import com.example.domain.profile.EventLoadPort
import com.example.domain.profile.ProfileLoadPort
import com.example.domain.profile.ProfileSavePort
import com.example.domain.profile.Scoring
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CachingProfileAdapterTest {
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
            val adapter =
                CachingProfileAdapter(
                    loadProfile = emptyLoadProfile(),
                    loadEvents = emptyLoadEvents(),
                    saveProfile = noOpSave(),
                )
            val jobs =
                (1L..count.toLong()).map { id ->
                    launch { adapter.update(1L, event(id), itemVec(id.toInt())) }
                }
            jobs.forEach { it.join() }
            val profile = adapter.getOrLoad(1L)
            assertEquals(count.toLong(), profile.lastAppliedEventId)
        }
    }

    @Test
    fun `LRU evicts oldest user when capacity exceeded`() {
        runBlocking {
            val adapter =
                CachingProfileAdapter(
                    loadProfile = emptyLoadProfile(),
                    loadEvents = emptyLoadEvents(),
                    saveProfile = noOpSave(),
                    cacheCapacity = 2L,
                )
            adapter.update(1L, event(1L), itemVec(1))
            adapter.update(2L, event(2L), itemVec(2))
            adapter.update(3L, event(3L), itemVec(3)) // should evict user 1
            assertNotNull(adapter.getOrLoad(2L))
            assertNotNull(adapter.getOrLoad(3L))
        }
    }

    @Test
    fun `snapshotDirty is idempotent — second call returns empty`() {
        runBlocking {
            val adapter =
                CachingProfileAdapter(
                    loadProfile = emptyLoadProfile(),
                    loadEvents = emptyLoadEvents(),
                    saveProfile = noOpSave(),
                )
            adapter.update(1L, event(1L), itemVec(1))
            val first = adapter.snapshotDirty()
            val second = adapter.snapshotDirty()
            assertTrue(first.isNotEmpty(), "First snapshot should have dirty entries")
            assertTrue(second.isEmpty(), "Second snapshot should be empty after drain")
        }
    }
}
