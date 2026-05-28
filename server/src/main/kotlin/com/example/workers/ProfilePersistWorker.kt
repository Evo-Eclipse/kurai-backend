package com.example.workers

import com.example.application.profile.CachingProfileAdapter
import com.example.infrastructure.sqlite.ProfileRepository
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

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
        } finally {
            withContext(NonCancellable) { flush() }
        }
    }

    internal fun flush() {
        cachingProfile.snapshotDirty().forEach { (_, profile) ->
            profileRepo.upsert(profile.userId, profile.embeddingVersion.value, profile.lastAppliedEventId)
        }
    }
}
