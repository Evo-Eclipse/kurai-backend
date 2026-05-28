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
    // Format: uint32 k | uint32 dim | k x dim x float32
    private fun syntheticBinary(
        k: Int,
        dim: Int = 16,
    ): ByteArray {
        val buf = ByteBuffer.allocate(8 + k * dim * 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(k)
        buf.putInt(dim)
        repeat(k) { i ->
            repeat(dim) { d -> buf.putFloat(if (d == i % dim) 1f else 0f) }
        }
        return buf.array()
    }

    private fun serviceWith(
        k: Int,
        dim: Int = 16,
    ): ClusterService = ClusterService.fromCentroids(loadCentroids(ByteArrayInputStream(syntheticBinary(k, dim))))

    private fun coldProfile() = UserProfile.coldStart(1L)

    private fun concentratedProfile(dim: Int): UserProfile {
        val vec0 = Scoring.l2Normalize(FloatArray(dim).also { it[0] = 1f })
        return UserProfile(
            userId = 1L,
            embeddingVersion = EmbeddingVersion("v1"),
            positivePrototypes = listOf(Prototype(vec0, 1f)),
            negativePrototypes = emptyList(),
            sessionVector = FloatArray(Prototype.VECTOR_DIM),
            longTermVector = FloatArray(Prototype.VECTOR_DIM),
            lastAppliedEventId = 0L,
        )
    }

    @Test
    fun `fromCentroids accepts arbitrary k and dim`() {
        for ((k, dim) in listOf(1 to 4, 8 to 16, 24 to 768, 100 to 32)) {
            val service = serviceWith(k, dim)
            assertEquals(k, service.size)
            assertEquals(dim, service.dim)
        }
    }

    @Test
    fun `loadCentroids with k=0 throws`() {
        val buf =
            ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).also {
                it.putInt(0)
                it.putInt(16)
            }
        assertFailsWith<IllegalStateException> { loadCentroids(ByteArrayInputStream(buf.array())) }
    }

    @Test
    fun `loadCentroids with dim=0 throws`() {
        val buf =
            ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).also {
                it.putInt(8)
                it.putInt(0)
            }
        assertFailsWith<IllegalStateException> { loadCentroids(ByteArrayInputStream(buf.array())) }
    }

    @Test
    fun `assignCluster rejects vector with wrong dimension`() {
        val service = serviceWith(k = 4, dim = 16)
        assertFailsWith<IllegalArgumentException> { service.assignCluster(FloatArray(8)) }
    }

    @Test
    fun `epsilonCandidates on cold profile returns k distinct indices`() {
        val service = serviceWith(k = 8)
        val result = service.epsilonCandidates(coldProfile(), k = 5, seed = 42L)
        assertEquals(5, result.size)
        assertEquals(result.size, result.toSet().size, "Indices must be distinct")
        assertTrue(result.all { it in 0 until 8 })
    }

    @Test
    fun `epsilonCandidates on concentrated profile avoids occupied cluster`() {
        // Prototype requires VECTOR_DIM vectors, so cluster service must use the same dim.
        val dim = Prototype.VECTOR_DIM
        val service = serviceWith(k = 8, dim = dim)
        val profile = concentratedProfile(dim)
        val occupiedCluster = service.assignCluster(profile.positivePrototypes[0].vector)
        val result = service.epsilonCandidates(profile, k = 7, seed = 0L)
        assertTrue(
            occupiedCluster !in result,
            "Occupied cluster $occupiedCluster should not be in epsilon candidates $result",
        )
    }

    @Test
    fun `epsilonCandidates is deterministic for fixed seed`() {
        val service = serviceWith(k = 8)
        val r1 = service.epsilonCandidates(coldProfile(), k = 5, seed = 12345L)
        val r2 = service.epsilonCandidates(coldProfile(), k = 5, seed = 12345L)
        assertEquals(r1, r2)
    }
}
