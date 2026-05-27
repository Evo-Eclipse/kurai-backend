package com.example.domain.cluster

import com.example.domain.model.EmbeddingVersion
import com.example.domain.model.Prototype
import com.example.domain.model.UserProfile
import com.example.domain.profile.Scoring
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ClusterServiceTest {
    private fun syntheticBinary(k: Int): ByteArray {
        val buf = ByteBuffer.allocate(4 + k * EXPECTED_DIM * 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(k)
        repeat(k) { i ->
            // Each centroid is a unit vector along dimension i % EXPECTED_DIM
            repeat(EXPECTED_DIM) { d -> buf.putFloat(if (d == i % EXPECTED_DIM) 1f else 0f) }
        }
        return buf.array()
    }

    private fun coldProfile() = UserProfile.coldStart(1L)

    private fun concentratedProfile(): UserProfile {
        // All prototypes near centroid 0 (unit vector along dim 0)
        val vec0 = Scoring.l2Normalize(FloatArray(EXPECTED_DIM).also { it[0] = 1f })
        val ev = EmbeddingVersion("v1")
        return UserProfile(
            userId = 1L,
            embeddingVersion = ev,
            positivePrototypes = listOf(Prototype(vec0, 1f)),
            negativePrototypes = emptyList(),
            sessionVector = FloatArray(Prototype.VECTOR_DIM),
            longTermVector = FloatArray(Prototype.VECTOR_DIM),
            lastAppliedEventId = 0L,
        )
    }

    @Test
    fun `fromCentroids with valid 23x768 binary succeeds`() {
        val bytes = syntheticBinary(EXPECTED_K)
        val service = ClusterService.fromCentroids(loadCentroids(ByteArrayInputStream(bytes)))
        // assignCluster should return a valid index
        val vec = FloatArray(EXPECTED_DIM).also { it[0] = 1f }
        val idx = service.assignCluster(vec)
        assertTrue(idx in 0 until EXPECTED_K)
    }

    @Test
    fun `loadCentroids with wrong k throws IllegalStateException`() {
        val bytes = syntheticBinary(22)
        assertFailsWith<IllegalStateException> {
            loadCentroids(ByteArrayInputStream(bytes))
        }
    }

    @Test
    fun `epsilonCandidates on cold profile returns k distinct indices`() {
        val service = ClusterService.fromCentroids(loadCentroids(ByteArrayInputStream(syntheticBinary(EXPECTED_K))))
        val result = service.epsilonCandidates(coldProfile(), k = 5, seed = 42L)
        assertEquals(5, result.size)
        assertEquals(result.size, result.toSet().size, "Indices must be distinct")
        assertTrue(result.all { it in 0 until EXPECTED_K })
    }

    @Test
    fun `epsilonCandidates on concentrated profile avoids occupied cluster`() {
        val service = ClusterService.fromCentroids(loadCentroids(ByteArrayInputStream(syntheticBinary(EXPECTED_K))))
        val profile = concentratedProfile()
        val occupiedCluster = service.assignCluster(profile.positivePrototypes[0].vector)
        val result = service.epsilonCandidates(profile, k = EXPECTED_K - 1, seed = 0L)
        // The occupied cluster should not appear among the first EXPECTED_K-1 picks
        assertTrue(
            occupiedCluster !in result,
            "Occupied cluster $occupiedCluster should not be in epsilon candidates $result",
        )
    }

    @Test
    fun `epsilonCandidates is deterministic for fixed seed`() {
        val service = ClusterService.fromCentroids(loadCentroids(ByteArrayInputStream(syntheticBinary(EXPECTED_K))))
        val r1 = service.epsilonCandidates(coldProfile(), k = 5, seed = 12345L)
        val r2 = service.epsilonCandidates(coldProfile(), k = 5, seed = 12345L)
        assertEquals(r1, r2)
    }
}
