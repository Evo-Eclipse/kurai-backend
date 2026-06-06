package com.example.content

import com.example.ErrorDetail
import com.example.ErrorResponse
import com.example.application.acquisition.AcquisitionService
import com.example.application.content.EnrichOutcome
import com.example.application.content.ImageMetadata
import com.example.application.content.MetadataService
import com.example.domain.content.ContentSource
import com.example.domain.content.RawImage
import com.example.domain.content.SourceQuery
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import java.security.MessageDigest

@Serializable
data class ProxyRequest(
    val source: String,
    val tags: List<String>,
    val limit: Int,
)

@Serializable
data class ShuttleUrlsRequest(
    val urls: List<String>,
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
 * Thin HTTP adapter for the two content modes; all enrichment policy lives in
 * [MetadataService].
 *
 * - `POST /content/proxy` — Kurai is the front door: it forwards the caller's
 *   tags to an upstream [ContentSource], downloads the images, enriches them,
 *   and returns CDN URLs + metadata synchronously. The catalog write happens
 *   write-behind on [persistScope] after the response is sent.
 * - `POST /content/shuttle` — the caller already fetched images elsewhere and
 *   sends either a JSON list of CDN URLs (downloaded via the `cdn` source) or
 *   raw bytes (multipart). Kurai returns metadata and archives the bytes to
 *   Space write-behind, without enrolling them in the catalog or vector index.
 */
class ContentHandler(
    private val metadataService: MetadataService,
    private val acquisitionService: AcquisitionService,
    private val contentSources: Map<String, ContentSource>,
    private val persistScope: CoroutineScope,
    private val activeEmbeddingVersion: suspend () -> String,
    private val maxImages: Int = DEFAULT_MAX_IMAGES,
    private val maxImageBytes: Long = DEFAULT_MAX_IMAGE_BYTES,
) {
    suspend fun handleProxy(call: ApplicationCall) {
        val userId = call.requireAuthenticatedUserId() ?: return
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

        val collected = mutableListOf<RawImage>()
        source.fetch(SourceQuery(req.tags, req.limit)) { image -> collected.add(image) }

        when (val outcome = metadataService.enrich(userId, collected.map { it.cdnUrl to it.bytes })) {
            is EnrichOutcome.Enriched -> {
                call.respond(ContentResponse(outcome.items.map { it.toResponse() }))
                // Write-behind: persist the proxied images after responding,
                // reusing the embeddings already computed during enrichment.
                val batch = collected.zip(outcome.items) { img, meta -> img to meta.vector.toFloatArray() }
                val version = activeEmbeddingVersion()
                persistScope.launch { acquisitionService.persistBatch(batch, version) }
            }

            EnrichOutcome.VersionMismatch -> respondVersionMismatch(call)
        }
    }

    suspend fun handleShuttle(call: ApplicationCall) {
        val userId = call.requireAuthenticatedUserId() ?: return
        val type = call.request.contentType()
        val images: List<Pair<String, ByteArray>> =
            when {
                type.match(ContentType.Application.Json) -> receiveUrls(call) ?: return
                type.match(ContentType.MultiPart.FormData) -> receiveUploads(call)
                else -> {
                    call.respond(
                        HttpStatusCode.UnsupportedMediaType,
                        ErrorResponse(ErrorDetail("UNSUPPORTED_MEDIA_TYPE")),
                    )
                    return
                }
            }

        when (val outcome = metadataService.enrich(userId, images)) {
            is EnrichOutcome.Enriched -> {
                call.respond(ContentResponse(outcome.items.map { it.toResponse() }))
                // Write-behind: archive the client-supplied bytes to Space after
                // responding. Shuttle keeps the images but does not enrol them in
                // the catalog/index (unlike proxy), so only the object store is hit.
                val bytes = images.map { it.second }
                persistScope.launch { acquisitionService.archiveToStore(bytes) }
            }

            EnrichOutcome.VersionMismatch -> respondVersionMismatch(call)
        }
    }

    /** Downloads the caller-supplied CDN URLs through the shared `cdn` source. */
    private suspend fun receiveUrls(call: ApplicationCall): List<Pair<String, ByteArray>>? {
        val req = call.receive<ShuttleUrlsRequest>()
        if (req.urls.isEmpty() || req.urls.size > maxImages) {
            call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse(ErrorDetail("INVALID_IMAGE_COUNT")))
            return null
        }
        val cdn = contentSources[CDN_SOURCE] ?: error("cdn content source not registered")
        val out = mutableListOf<Pair<String, ByteArray>>()
        cdn.fetch(SourceQuery(req.urls, req.urls.size)) { image -> out.add(image.cdnUrl to image.bytes) }
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
                val bytes = part.provider().readRemaining().readByteArray()
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

    private fun ImageMetadata.toResponse(): ImageMetadataResponse =
        ImageMetadataResponse(ref = ref, embeddingVersion = embeddingVersion, vector = vector, score = score)

    private fun md5Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        /** Content-source key reused to download caller-supplied shuttle URLs. */
        const val CDN_SOURCE = "cdn"

        /** Max tags forwarded to an upstream source on a proxy request. */
        const val MAX_TAGS = 10_000

        /** Default per-request image cap (proxy limit and content batch size). */
        const val DEFAULT_MAX_IMAGES = 30

        /** Default per-image byte cap for downloaded/uploaded content media (10 MiB). */
        const val DEFAULT_MAX_IMAGE_BYTES = 10L * 1024 * 1024

        /** Retry-After (seconds) sent with a 503 embedding-version mismatch. */
        const val RETRY_AFTER_VERSION_MISMATCH_SEC = 30
    }
}
