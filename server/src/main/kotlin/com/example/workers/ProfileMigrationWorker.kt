package com.example.workers

import com.example.application.embedding.CachingEmbeddingAdapter
import com.example.application.profile.CachingProfileAdapter
import com.example.domain.model.EmbeddingVersion
import com.example.domain.model.UserEvent
import com.example.domain.model.UserProfile
import com.example.domain.profile.Scoring
import com.example.infrastructure.sqlite.EventRepository
import com.example.infrastructure.sqlite.ProfileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(ProfileMigrationWorker::class.java)

class ProfileMigrationWorker(
    private val profileRepo: ProfileRepository,
    private val eventRepo: EventRepository,
    private val cachingEmbedding: CachingEmbeddingAdapter,
    private val cachingProfile: CachingProfileAdapter,
    private val activeEmbeddingVersion: () -> EmbeddingVersion,
    private val scanIntervalMs: Long = SCAN_INTERVAL_MS,
) {
    suspend fun run() {
        while (true) {
            migrateOneBatch()
            delay(scanIntervalMs)
        }
    }

    internal suspend fun migrateOneBatch() {
        val active = activeEmbeddingVersion()
        val staleIds =
            withContext(Dispatchers.IO) {
                profileRepo.findStaleVersions(active.value)
            }
        if (staleIds.isEmpty()) return

        log.info("ProfileMigrationWorker: migrating ${staleIds.size} stale profiles to version ${active.value}")
        for (userId in staleIds) {
            migrateUser(userId, active)
        }
    }

    private suspend fun migrateUser(
        userId: Long,
        targetVersion: EmbeddingVersion,
    ) {
        val positiveEvents =
            withContext(Dispatchers.IO) {
                eventRepo.loadPositiveSince(userId, sinceEventId = 0L)
            }
        val vecs = cachingEmbedding.lookupVectors(positiveEvents.map { it.itemId })
        val rebuiltProfile =
            positiveEvents.fold(UserProfile.coldStart(userId, targetVersion)) { acc, ev ->
                val vec = vecs[ev.itemId] ?: return@fold acc
                val userEvent =
                    UserEvent(
                        id = 0L,
                        userId = userId,
                        itemId = ev.itemId,
                        weight = ev.weight,
                        embeddingVersion = targetVersion,
                        ts = 0L,
                    )
                Scoring.applyEma(acc, userEvent, vec)
            }
        val maxId =
            withContext(Dispatchers.IO) {
                eventRepo.maxEventId(userId)
            }
        withContext(Dispatchers.IO) {
            profileRepo.upsert(userId, targetVersion.value, maxId)
        }
        cachingProfile.invalidate(userId)
        log.debug("ProfileMigrationWorker: migrated userId=$userId to version=${targetVersion.value}")
    }

    companion object {
        const val SCAN_INTERVAL_MS: Long = 60_000L
    }
}
