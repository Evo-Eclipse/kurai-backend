package com.example.application.profile

import com.example.application.embedding.CachingEmbeddingAdapter
import com.example.domain.model.EmbeddingVersion
import com.example.domain.model.Prototype
import com.example.domain.model.UserProfile
import com.example.domain.profile.Scoring
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Ranking policy is exercised here without the HTTP layer -- the win from
 * extracting it out of the handler. Cluster-backed cold-start paths (which
 * need the clusters.bin fixture) stay covered in the server-side
 * RankingHandlerTest.
 */
class RankingServiceTest {
    private fun basisVec(dim: Int): FloatArray {
        val v = FloatArray(Prototype.VECTOR_DIM)
        v[dim] = 1f
        return v
    }

    private fun normalVec(seed: Int): FloatArray =
        Scoring.l2Normalize(FloatArray(Prototype.VECTOR_DIM) { (it + seed + 1).toFloat() })

    private fun profileAdapter(profile: UserProfile) =
        CachingProfileAdapter(loadProfile = { profile }, loadEvents = { _, _ -> emptyList() })

    private fun embeddingAdapter(vectors: Map<Long, FloatArray>) =
        CachingEmbeddingAdapter(lookupFromStore = { ids ->
            ids.mapNotNull { id -> vectors[id]?.let { id to it } }.toMap()
        })

    private fun warmProfile(version: String = "v1") =
        UserProfile(
            userId = 1L,
            embeddingVersion = EmbeddingVersion(version),
            positivePrototypes =
                listOf(
                    Prototype(basisVec(0), 1f),
                    Prototype(basisVec(1), 1f),
                    Prototype(basisVec(2), 1f),
                ),
            negativePrototypes = emptyList(),
            sessionVector = FloatArray(Prototype.VECTOR_DIM),
            longTermVector = FloatArray(Prototype.VECTOR_DIM),
            lastAppliedEventId = 0L,
        )

    private fun coldProfile() =
        UserProfile(
            userId = 1L,
            embeddingVersion = EmbeddingVersion("v1"),
            positivePrototypes = emptyList(),
            negativePrototypes = emptyList(),
            sessionVector = FloatArray(Prototype.VECTOR_DIM),
            longTermVector = FloatArray(Prototype.VECTOR_DIM),
            lastAppliedEventId = 0L,
        )

    @Test
    fun `stale embedding version yields VersionMismatch`() =
        runTest {
            val service =
                RankingService(
                    profileAdapter(warmProfile("v1")),
                    embeddingAdapter(emptyMap()),
                    getClusterService = { null },
                    activeEmbeddingVersion = { EmbeddingVersion("v2") },
                )
            val outcome = service.rank(userId = 1L, candidateIds = listOf(1L), topK = 1)
            assertEquals(RankingOutcome.VersionMismatch, outcome)
        }

    @Test
    fun `warm profile ranks aligned candidates highest`() =
        runTest {
            val vecs =
                (1L..5L).associate { id ->
                    id to
                        if (id <= 3L) basisVec((id - 1).toInt()) else normalVec(id.toInt())
                }
            val service =
                RankingService(
                    profileAdapter(warmProfile()),
                    embeddingAdapter(vecs),
                    getClusterService = { null },
                    activeEmbeddingVersion = { EmbeddingVersion("v1") },
                )
            val outcome = service.rank(userId = 1L, candidateIds = (1L..5L).toList(), topK = 3)
            val ranked = assertIs<RankingOutcome.Ranked>(outcome)
            assertEquals(3, ranked.items.size)
            val ids = ranked.items.map { it.itemId }.toSet()
            assertTrue(1L in ids && 2L in ids && 3L in ids, "aligned items must rank top: $ids")
        }

    @Test
    fun `cold profile with no cluster service falls back to candidate order`() =
        runTest {
            val vecs = (1L..5L).associate { id -> id to normalVec(id.toInt()) }
            val service =
                RankingService(
                    profileAdapter(coldProfile()),
                    embeddingAdapter(vecs),
                    getClusterService = { null },
                    activeEmbeddingVersion = { EmbeddingVersion("v1") },
                )
            val outcome = service.rank(userId = 1L, candidateIds = (1L..5L).toList(), topK = 3)
            val ranked = assertIs<RankingOutcome.Ranked>(outcome)
            assertEquals(listOf(1L, 2L, 3L), ranked.items.map { it.itemId })
        }
}
