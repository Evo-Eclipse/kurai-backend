package com.example.application.profile

import com.example.application.profile.CachingProfileAdapter
import com.example.infrastructure.sqlite.ProfileRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(ProfilePersistWorker::class.java)

class ProfilePersistWorker(
    private val cachingProfile: CachingProfileAdapter,
    private val profileRepo: ProfileRepository,
    private val intervalMs: Long = 30_000,
) {
    suspend fun run() {
        try {
            while (true) {
                delay(intervalMs)
                flush()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("ProfilePersistWorker crashed; worker stopped permanently", e)
        } finally {
            withContext(NonCancellable) { flush() }
        }
    }

    internal suspend fun flush() {
        val dirty = cachingProfile.snapshotDirty()
        if (dirty.isEmpty()) return
        dirty.values.forEach { profile ->
            profileRepo.upsert(profile.userId, profile.embeddingVersion.value, profile.lastAppliedEventId)
        }
    }
}
