package com.example.application.content

import com.example.application.profile.CachingProfileAdapter
import com.example.domain.inference.InferenceService
import com.example.domain.model.EmbeddingVersion
import com.example.domain.model.Prototype
import com.example.domain.model.UserProfile
import com.example.domain.profile.Scoring
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MetadataServiceTest {
    private fun fakeInference(): InferenceService =
        InferenceService(
            preprocess = { FloatArray(3 * 224 * 224) },
            infer = { Scoring.l2Normalize(FloatArray(Prototype.VECTOR_DIM) { 1f }) },
        )

    private fun profileAdapter(
        version: String,
        positives: List<Prototype> = emptyList(),
    ): CachingProfileAdapter =
        CachingProfileAdapter(
            loadProfile = { userId ->
                UserProfile(
                    userId = userId,
                    embeddingVersion = EmbeddingVersion(version),
                    positivePrototypes = positives,
                    negativePrototypes = emptyList(),
                    sessionVector = FloatArray(Prototype.VECTOR_DIM),
                    longTermVector = FloatArray(Prototype.VECTOR_DIM),
                    lastAppliedEventId = 0L,
                )
            },
            loadEvents = { _, _ -> emptyList() },
        )

    private fun service(
        version: String,
        active: String,
        positives: List<Prototype> = emptyList(),
    ): MetadataService =
        MetadataService(
            inferenceService = fakeInference(),
            cachingProfile = profileAdapter(version, positives),
            activeEmbeddingVersion = { EmbeddingVersion(active) },
        )

    @Test
    fun `enrich embeds and scores each image preserving input order`() =
        runTest {
            val images = listOf("a" to ByteArray(1), "b" to ByteArray(2), "c" to ByteArray(3))
            val outcome = service(version = "v1", active = "v1").enrich(userId = 1L, images = images)

            val enriched = assertIs<EnrichOutcome.Enriched>(outcome)
            assertEquals(listOf("a", "b", "c"), enriched.items.map { it.ref })
            assertEquals(
                Prototype.VECTOR_DIM,
                enriched.items
                    .first()
                    .vector.size,
            )
            assertTrue(enriched.items.all { it.embeddingVersion == "v1" })
        }

    @Test
    fun `enrich returns VersionMismatch when the profile is on a stale version`() =
        runTest {
            val outcome = service(version = "v1", active = "v2").enrich(1L, listOf("a" to ByteArray(1)))
            assertIs<EnrichOutcome.VersionMismatch>(outcome)
        }

    @Test
    fun `enrich on empty input returns an empty enriched result`() =
        runTest {
            val outcome = service(version = "v1", active = "v1").enrich(1L, emptyList())
            val enriched = assertIs<EnrichOutcome.Enriched>(outcome)
            assertTrue(enriched.items.isEmpty())
        }

    @Test
    fun `enrich returns EmbedFailed when inference throws`() =
        runTest {
            val failing =
                MetadataService(
                    inferenceService =
                        InferenceService(
                            preprocess = { error("bad image") },
                            infer = { FloatArray(Prototype.VECTOR_DIM) },
                        ),
                    cachingProfile = profileAdapter("v1"),
                    activeEmbeddingVersion = { EmbeddingVersion("v1") },
                )
            val outcome = failing.enrich(1L, listOf("a" to ByteArray(1)))
            assertIs<EnrichOutcome.EmbedFailed>(outcome)
        }

    @Test
    fun `enrich scores high against a matching positive prototype`() =
        runTest {
            // The fake embedder always returns the same normalized vector; a positive
            // prototype equal to it yields cosine ~1, so the personalized score ~1.
            val embed = Scoring.l2Normalize(FloatArray(Prototype.VECTOR_DIM) { 1f })
            val outcome =
                service(version = "v1", active = "v1", positives = listOf(Prototype(embed, 1f)))
                    .enrich(1L, listOf("a" to ByteArray(1)))

            val enriched = assertIs<EnrichOutcome.Enriched>(outcome)
            assertTrue(enriched.items.first().score > 0.99f)
        }
}
