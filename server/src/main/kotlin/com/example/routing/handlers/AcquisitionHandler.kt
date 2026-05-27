package com.example.routing.handlers

import com.example.application.acquisition.AcquisitionService
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class AcquisitionRunRequest(
    val source: String,
    val tags: List<String>,
    val limit: Int,
)

@Serializable
data class AcquisitionRunResponse(
    val jobId: String,
)

@Serializable
data class AcquisitionJobResponse(
    val id: String,
    val status: String,
    val createdAt: Long,
    val completedAt: Long?,
    val errorMessage: String?,
)

class AcquisitionHandler(
    private val acquisitionService: AcquisitionService,
    private val scope: CoroutineScope,
    maxConcurrentJobs: Int = MAX_CONCURRENT_JOBS,
) {
    private val jobSemaphore = Semaphore(maxConcurrentJobs)

    suspend fun handleRun(call: ApplicationCall) {
        val req = call.receive<AcquisitionRunRequest>()
        if (req.source !in acquisitionService.knownSources()) {
            throw BadRequestException("Unknown source: ${req.source}")
        }
        if (req.limit < 0 || req.limit > MAX_LIMIT) {
            throw BadRequestException("limit must be between 0 and $MAX_LIMIT")
        }
        if (req.tags.size > MAX_TAGS) {
            throw BadRequestException("tags list must not exceed $MAX_TAGS entries")
        }
        if (!jobSemaphore.tryAcquire()) {
            call.response.headers.append(HttpHeaders.RetryAfter, "5")
            call.respond(HttpStatusCode.TooManyRequests)
            return
        }
        val jobId = UUID.randomUUID().toString()
        acquisitionService.createJob(jobId, req.source, req.tags)
        scope.launch {
            try {
                acquisitionService.run(jobId, req.source, req.tags, req.limit)
            } finally {
                jobSemaphore.release()
            }
        }
        call.respond(HttpStatusCode.Accepted, AcquisitionRunResponse(jobId))
    }

    suspend fun handleGetJob(call: ApplicationCall) {
        val id = call.parameters["id"] ?: throw BadRequestException("Missing id")
        val job = acquisitionService.getJob(id) ?: return call.respond(HttpStatusCode.NotFound)
        call.respond(
            AcquisitionJobResponse(
                id = job.id,
                status = job.status,
                createdAt = job.createdAt,
                completedAt = job.completedAt,
                errorMessage = job.errorMessage,
            ),
        )
    }

    companion object {
        const val MAX_CONCURRENT_JOBS = 4
        const val MAX_LIMIT = 10_000
        const val MAX_TAGS = 10_000
    }
}
