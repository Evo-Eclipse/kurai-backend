package com.example.domain.profile

import com.example.domain.model.EmbeddingVersion
import com.example.domain.model.Prototype
import com.example.domain.model.UserEvent
import com.example.domain.model.UserProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

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

    @Test
    fun `getOrLoad replays events from EventLoadPort`() {
        val replayEvents =
            listOf(
                event(10L) to itemVec(1),
                event(20L) to itemVec(2),
                event(30L) to itemVec(3),
            )
        val service =
            ProfileService(
                loadProfile = { null },
                loadEvents = { _, _ -> replayEvents },
                saveProfile = { _ -> },
            )
        val profile = service.getOrLoad(1L)
        assertEquals(30L, profile.lastAppliedEventId)
    }

    @Test
    fun `update applies EMA and calls saveProfile`() {
        var saved: UserProfile? = null
        val service =
            ProfileService(
                loadProfile = { null },
                loadEvents = { _, _ -> emptyList() },
                saveProfile = { saved = it },
            )
        service.update(1L, event(7L), itemVec(1))
        assertNotNull(saved)
        assertEquals(7L, saved.lastAppliedEventId)
    }
}
