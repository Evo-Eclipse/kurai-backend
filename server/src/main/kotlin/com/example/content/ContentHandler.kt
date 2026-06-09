package com.example.content

import com.example.ErrorDetail
import com.example.ErrorResponse
import com.example.application.acquisition.AcquisitionService
import com.example.application.content.EnrichOutcome
import com.example.application.content.ImageMetadata
import com.example.application.content.MetadataService
import com.example.domain.content.ContentItem
import com.example.domain.content.ContentSource
import com.example.domain.content.RawImage
import com.example.domain.content.SourceQuery
import com.example.domain.content.md5Hex
import com.example.requireAuthenticatedUserId
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.contentType
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable

@Serializable
data class ProxyRequest(
    val source: String,
    val tags: List<String>,
    val limit: Int,
)

@Serializable
data class ContentItemResponse(
    val ref: String,
    val sourceId: String,
    val platform: String,
    val originPostUrl: String,
    val rating: String?,
)

@Serializable
data class ContentListResponse(
    val items: List<ContentItemResponse>,
)

@Serializable
data class ScoresRequest(
    val urls: List<String>,
    /** `archive` (default) keeps bytes in Space only; `catalog` also enrols them. */
    val persist: String = "archive",
)

@Serializable
data class ImageMetadataResponse(
    val ref: String,
    val embeddingVersion: String,
    val vector: List<Float>,
    val score: Float,
)

@Serializable
data class ContentResponse(
    val items: List<ImageMetadataResponse>,
)

/**
 * Thin HTTP adapter for the content surface; all enrichment policy lives in
 * [MetadataService]. The two modes share one scoring endpoint:
 *
 * - `POST /content/proxy` — Kurai searches the upstream [ContentSource] with
 *   server credentials and returns a cheap **list** of refs (one search call,
 *   no binaries, no ML). The client can render/animate immediately.
 * - `POST /content/scores` — the scoring phase shared by both modes. Takes
 *   either a JSON list of CDN URLs (proxy: the refs from the list call;
 *   shuttle: refs the client fetched itself) or multipart uploads, downloads/
 *   embeds/scores them, and returns metadata. Bytes are archived to Space
 *   write-behind; URL requests may opt into full catalog ingestion
 *   (`persist=catalog`). Uploads are always archive-only (no upstream origin).
 */
class ContentHandler(
    private val metadataService: MetadataService,
    private val acquisitionService: AcquisitionService,
    private val contentSources: Map<String, ContentSource>,
    private val persistScope: CoroutineScope,
    private val activeEmbeddingVersion: suspend () -> String,
    private val maxImages: Int = DEFAULT_MAX_IMAGES,
    private val maxImageBytes: Long = DEFAULT_MAX_IMAGE_BYTES,
    /** Notified when a write-behind persist fails after the response was sent. */
    private val onPersistFailure: (Throwable) -> Unit = {},
) {
    suspend fun handleProxy(call: ApplicationCall) {
        call.requireAuthenticatedUserId() ?: return
        val req = call.receive<ProxyRequest>()
        val source =
            contentSources[req.source]
                ?: throw BadRequestException("Unknown source: ${req.source}")
        if (req.limit !in 1..maxImages) {
            call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse(ErrorDetail("INVALID_LIMIT")))
            return
        }
        if (req.tags.size > MAX_TAGS) {
            call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse(ErrorDetail("INVALID_TAGS")))
            return
        }
        val items = source.search(SourceQuery(req.tags, req.limit))
        call.respond(ContentListResponse(items.map { it.toListResponse() }))
    }

    suspend fun handleScores(call: ApplicationCall) {
        val userId = call.requireAuthenticatedUserId() ?: return
        val type = call.request.contentType()
        when {
            type.match(ContentType.Application.Json) -> handleUrlScores(call, userId)
            type.match(ContentType.MultiPart.FormData) -> handleUploadScores(call, userId)
            else ->
                call.respond(
                    HttpStatusCode.UnsupportedMediaType,
                    ErrorResponse(ErrorDetail("UNSUPPORTED_MEDIA_TYPE")),
                )
        }
    }

    /** Scores caller-supplied CDN URLs (downloaded via the `cdn` source). */
    private suspend fun handleUrlScores(
        call: ApplicationCall,
        userId: Long,
    ) {
        val req = call.receive<ScoresRequest>()
        if (req.urls.isEmpty() || req.urls.size > maxImages) {
            call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse(ErrorDetail("INVALID_IMAGE_COUNT")))
            return
        }
        val mode = Persist.parse(req.persist)
        if (mode == null) {
            call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse(ErrorDetail("INVALID_PERSIST")))
            return
        }
        val raws =
            try {
                downloadUrls(req.urls)
            } catch (e: IllegalArgumentException) {
                // A caller URL the SSRF guard rejected is a client error, not a 500.
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(ErrorDetail("INVALID_URL")))
                return
            }
        when (val outcome = metadataService.enrich(userId, raws.map { it.cdnUrl to it.bytes })) {
            is EnrichOutcome.Enriched -> {
                call.respond(ContentResponse(outcome.items.map { it.toResponse() }))
                persistInBackground {
                    val version = activeEmbeddingVersion()
                    when (mode) {
                        Persist.CATALOG ->
                            acquisitionService.persistBatch(
                                raws.zip(outcome.items) { raw, meta -> raw to meta.vector.toFloatArray() },
                                version,
                            )
                        Persist.ARCHIVE -> acquisitionService.archiveToStore(raws.map { it.bytes })
                    }
                }
            }

            EnrichOutcome.VersionMismatch -> respondVersionMismatch(call)
            EnrichOutcome.EmbedFailed -> respondEmbedFailed(call)
        }
    }

    /** Scores raw multipart uploads; archive-only (no upstream origin to catalog). */
    private suspend fun handleUploadScores(
        call: ApplicationCall,
        userId: Long,
    ) {
        val uploads = receiveUploads(call)
        when (val outcome = metadataService.enrich(userId, uploads)) {
            is EnrichOutcome.Enriched -> {
                call.respond(ContentResponse(outcome.items.map { it.toResponse() }))
                val bytes = uploads.map { it.second }
                persistInBackground { acquisitionService.archiveToStore(bytes) }
            }

            EnrichOutcome.VersionMismatch -> respondVersionMismatch(call)
            EnrichOutcome.EmbedFailed -> respondEmbedFailed(call)
        }
    }

    /**
     * Runs a persist step on [persistScope] after the response was already sent.
     * Failures cannot reach the client, so they are reported via [onPersistFailure]
     * instead of vanishing into the supervisor scope; cancellation still propagates.
     */
    private fun persistInBackground(block: suspend () -> Unit) {
        persistScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onPersistFailure(e)
            }
        }
    }

    /** Downloads the caller-supplied CDN URLs through the shared `cdn` source. */
    private suspend fun downloadUrls(urls: List<String>): List<RawImage> {
        val cdn = contentSources[CDN_SOURCE] ?: error("cdn content source not registered")
        val out = mutableListOf<RawImage>()
        cdn.fetch(SourceQuery(urls, urls.size)) { image -> out.add(image) }
        return out
    }

    /** Reads uploaded file parts as raw bytes, enforcing the count and size caps. */
    private suspend fun receiveUploads(call: ApplicationCall): List<Pair<String, ByteArray>> {
        val out = mutableListOf<Pair<String, ByteArray>>()
        call.receiveMultipart().forEachPart { part ->
            if (part is PartData.FileItem) {
                if (out.size >= maxImages) {
                    part.dispose()
                    throw BadRequestException("too many images (max $maxImages)")
                }
                // Read at most one byte past the cap so an oversized upload is
                // rejected without ever buffering the whole part into memory.
                val bytes = part.provider().readRemaining(maxImageBytes + 1).readByteArray()
                if (bytes.size > maxImageBytes) {
                    part.dispose()
                    throw BadRequestException("image exceeds max size ($maxImageBytes bytes)")
                }
                val ref = part.originalFileName?.takeIf { it.isNotBlank() } ?: md5Hex(bytes)
                out.add(ref to bytes)
            }
            part.dispose()
        }
        if (out.isEmpty()) throw BadRequestException("no file parts in multipart request")
        return out
    }

    private suspend fun respondVersionMismatch(call: ApplicationCall) {
        call.response.headers.append(HttpHeaders.RetryAfter, RETRY_AFTER_VERSION_MISMATCH_SEC.toString())
        call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse(ErrorDetail("EMBEDDING_VERSION_MISMATCH")))
    }

    private suspend fun respondEmbedFailed(call: ApplicationCall) {
        call.respond(HttpStatusCode.InternalServerError, ErrorResponse(ErrorDetail("EMBED_FAILED")))
    }

    private fun ContentItem.toListResponse(): ContentItemResponse =
        ContentItemResponse(
            ref = cdnUrl,
            sourceId = sourceId,
            platform = platform.id,
            originPostUrl = originPostUrl,
            rating = rating,
        )

    private fun ImageMetadata.toResponse(): ImageMetadataResponse =
        ImageMetadataResponse(ref = ref, embeddingVersion = embeddingVersion, vector = vector, score = score)

    private enum class Persist {
        ARCHIVE,
        CATALOG,
        ;

        companion object {
            fun parse(raw: String): Persist? =
                when (raw.lowercase()) {
                    "archive" -> ARCHIVE
                    "catalog" -> CATALOG
                    else -> null
                }
        }
    }

    companion object {
        /** Content-source key reused to download caller-supplied URLs for scoring. */
        const val CDN_SOURCE = "cdn"

        /** Max tags forwarded to an upstream source on a proxy request. */
        const val MAX_TAGS = 10_000

        /** Default per-request image cap (proxy list size and scoring batch size). */
        const val DEFAULT_MAX_IMAGES = 30

        /** Default per-image byte cap for downloaded/uploaded content media (10 MiB). */
        const val DEFAULT_MAX_IMAGE_BYTES = 10L * 1024 * 1024

        /** Retry-After (seconds) sent with a 503 embedding-version mismatch. */
        const val RETRY_AFTER_VERSION_MISMATCH_SEC = 30
    }
}
