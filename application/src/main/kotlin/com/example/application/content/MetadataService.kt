package com.example.application.content

import com.example.application.profile.CachingProfileAdapter
import com.example.domain.inference.InferenceService
import com.example.domain.model.EmbeddingVersion
import com.example.domain.profile.Scoring
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import java.util.concurrent.atomic.AtomicInteger

/** ML metadata for one image: its embedding plus the caller-personalized score. */
data class ImageMetadata(
    val ref: String,
    val embeddingVersion: String,
    val vector: List<Float>,
    val score: Float,
)

/** Outcome of an enrichment request; the handler maps each case to HTTP. */
sealed interface EnrichOutcome {
    data class Enriched(
        val items: List<ImageMetadata>,
    ) : EnrichOutcome

    /** The caller's profile is on a stale embedding version; cross-version cosine is meaningless. */
    data object VersionMismatch : EnrichOutcome

    /** At least one image could not be embedded; the batch is rejected as a whole. */
    data object EmbedFailed : EnrichOutcome
}

/**
 * Shared enrichment use case behind both content modes (proxy and shuttle):
 * embed each image with the active model and score it against the caller's
 * profile. Transport-agnostic and free of any HTTP / content-source concern,
 * so the policy is unit-testable; [com.example.content.ContentHandler] keeps only
 * request decoding and the [EnrichOutcome] -> HTTP mapping.
 *
 * The embedding-version gate mirrors
 * [com.example.application.profile.RankingService]: a profile pinned to a
 * superseded version cannot be compared with active-version vectors, so the
 * caller is asked to retry (the handler answers 503 + Retry-After).
 */
class MetadataService(
    private val inferenceService: InferenceService,
    private val cachingProfile: CachingProfileAdapter,
    private val activeEmbeddingVersion: suspend () -> EmbeddingVersion,
    private val embedConcurrency: Int = DEFAULT_EMBED_CONCURRENCY,
) {
    /**
     * Embeds and scores [images] (each a `ref to bytes`) for [userId]. Results
     * preserve input order. The embeds fan out under a [Semaphore] so a large
     * batch does not launch unbounded coroutines; the bounded ONNX dispatcher
     * remains the real cap on concurrent inference.
     */
    suspend fun enrich(
        userId: Long,
        images: List<Pair<String, ByteArray>>,
    ): EnrichOutcome {
        val active = activeEmbeddingVersion()
        val profile = cachingProfile.getOrLoad(userId)
        if (profile.embeddingVersion != active) {
            return EnrichOutcome.VersionMismatch
        }
        if (images.isEmpty()) {
            return EnrichOutcome.Enriched(emptyList())
        }

        val results = arrayOfNulls<ImageMetadata>(images.size)
        val failures = AtomicInteger(0)
        coroutineScope {
            val semaphore = Semaphore(embedConcurrency)
            images.forEachIndexed { index, (ref, bytes) ->
                semaphore.acquire()
                launch {
                    try {
                        val vec = inferenceService.embed(bytes)
                        results[index] =
                            ImageMetadata(
                                ref = ref,
                                embeddingVersion = active.value,
                                vector = vec.toList(),
                                score = Scoring.score(profile, vec),
                            )
                    } catch (_: Exception) {
                        failures.incrementAndGet()
                    } finally {
                        semaphore.release()
                    }
                }
            }
        }
        if (failures.get() > 0 || results.any { it == null }) {
            return EnrichOutcome.EmbedFailed
        }
        return EnrichOutcome.Enriched(results.map { checkNotNull(it) })
    }

    companion object {
        /** Max concurrent embed coroutines per enrichment request. */
        const val DEFAULT_EMBED_CONCURRENCY = 10
    }
}
