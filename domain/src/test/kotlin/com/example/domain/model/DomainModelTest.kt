package com.example.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame

class DomainModelTest {
    private fun normalizedVec(dim: Int = Prototype.VECTOR_DIM): FloatArray {
        val v = FloatArray(dim) { 1f }
        val norm = kotlin.math.sqrt(v.map { it * it }.sum().toDouble()).toFloat()
        return FloatArray(dim) { v[it] / norm }
    }

    private fun unnormalizedVec(): FloatArray = FloatArray(Prototype.VECTOR_DIM) { 0.001f }

    // region EmbeddingVersion

    @Test
    fun `EmbeddingVersion rejects blank value`() {
        assertFailsWith<IllegalArgumentException> { EmbeddingVersion("") }
        assertFailsWith<IllegalArgumentException> { EmbeddingVersion("   ") }
    }

    @Test
    fun `EmbeddingVersion accepts non-blank value`() {
        val v = EmbeddingVersion("vitb16-v1")
        assertEquals("vitb16-v1", v.value)
    }

    // endregion

    // region Prototype dimension checks

    @Test
    fun `Prototype rejects wrong dimension below 768`() {
        assertFailsWith<IllegalArgumentException> { Prototype(FloatArray(767), 1f) }
    }

    @Test
    fun `Prototype rejects wrong dimension above 768`() {
        assertFailsWith<IllegalArgumentException> { Prototype(FloatArray(769), 1f) }
    }

    @Test
    fun `Prototype debug-asserts L2 normalization`() {
        assertFailsWith<AssertionError> { Prototype(unnormalizedVec(), 1f) }
    }

    // endregion

    // region Prototype equality

    @Test
    fun `Prototype instances with content-equal FloatArrays are equal`() {
        val vec = normalizedVec()
        val a = Prototype(vec.copyOf(), 1f)
        val b = Prototype(vec.copyOf(), 1f)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `Prototype instances with different weight are not equal`() {
        val vec = normalizedVec()
        val a = Prototype(vec.copyOf(), 1f)
        val b = Prototype(vec.copyOf(), 0.5f)
        assert(a != b)
    }

    // endregion

    // region UserProfile

    @Test
    fun `UserProfile rejects sessionVector with wrong dimension`() {
        assertFailsWith<IllegalArgumentException> {
            UserProfile(
                userId = 1L,
                embeddingVersion = EmbeddingVersion("v1"),
                positivePrototypes = emptyList(),
                negativePrototypes = emptyList(),
                sessionVector = FloatArray(767),
                longTermVector = FloatArray(Prototype.VECTOR_DIM),
                lastAppliedEventId = 0L,
            )
        }
    }

    @Test
    fun `UserProfile rejects longTermVector with wrong dimension`() {
        assertFailsWith<IllegalArgumentException> {
            UserProfile(
                userId = 1L,
                embeddingVersion = EmbeddingVersion("v1"),
                positivePrototypes = emptyList(),
                negativePrototypes = emptyList(),
                sessionVector = FloatArray(Prototype.VECTOR_DIM),
                longTermVector = FloatArray(769),
                lastAppliedEventId = 0L,
            )
        }
    }

    @Test
    fun `UserProfile accepts zero sessionVector (cold-start sentinel)`() {
        val profile = UserProfile.coldStart(42L)
        assertEquals(42L, profile.userId)
        assertEquals(Prototype.VECTOR_DIM, profile.sessionVector.size)
    }

    @Test
    fun `UserProfile copy produces a distinct object`() {
        val original = UserProfile.coldStart(1L)
        val copy = original.copy(userId = 999L)
        assertNotSame(original, copy)
        assertEquals(999L, copy.userId)
        assertEquals(1L, original.userId)
    }

    // endregion

    // region UserEvent weight validation

    @Test
    fun `UserEvent rejects weight above 1`() {
        assertFailsWith<IllegalArgumentException> {
            UserEvent(1L, 1L, 1L, 1.1f, EmbeddingVersion("v1"), 1000L)
        }
    }

    @Test
    fun `UserEvent rejects weight below -1`() {
        assertFailsWith<IllegalArgumentException> {
            UserEvent(1L, 1L, 1L, -1.1f, EmbeddingVersion("v1"), 1000L)
        }
    }

    @Test
    fun `UserEvent accepts boundary weights`() {
        val ev = EmbeddingVersion("v1")
        UserEvent(1L, 1L, 1L, 1.0f, ev, 1000L)
        UserEvent(2L, 1L, 1L, -1.0f, ev, 1000L)
        UserEvent(3L, 1L, 1L, 0f, ev, 1000L)
    }

    @Test
    fun `UserEvent structural equality`() {
        val ev = EmbeddingVersion("v1")
        val a = UserEvent(1L, 2L, 3L, 1.0f, ev, 1000L)
        val b = UserEvent(1L, 2L, 3L, 1.0f, ev, 1000L)
        assertEquals(a, b)
    }

    // endregion
}
