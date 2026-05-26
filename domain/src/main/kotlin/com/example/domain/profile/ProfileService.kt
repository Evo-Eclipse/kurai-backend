package com.example.domain.profile

import com.example.domain.model.EmbeddingVersion
import com.example.domain.model.Prototype
import com.example.domain.model.UserEvent
import com.example.domain.model.UserProfile
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

class ProfileService(
    private val loadProfile: ProfileLoadPort,
    private val loadEvents: EventLoadPort,
    private val saveProfile: ProfileSavePort,
    cacheCapacity: Long = DEFAULT_CAPACITY,
) {
    private val cache: Cache<Long, UserProfile> =
        Caffeine.newBuilder().maximumSize(cacheCapacity).build()
    private val mutexes = ConcurrentHashMap<Long, Mutex>()
    private val dirtySet = ConcurrentHashMap.newKeySet<Long>()

    suspend fun getOrLoad(userId: Long): UserProfile =
        mutexFor(userId).withLock {
            cache.getIfPresent(userId)?.let { return it }
            val base = loadProfile(userId) ?: coldStart(userId)
            val events = loadEvents(userId, base.lastAppliedEventId)
            val profile = events.fold(base) { acc, (ev, vec) -> Scoring.applyEma(acc, ev, vec) }
            cache.put(userId, profile)
            profile
        }

    suspend fun update(
        userId: Long,
        event: UserEvent,
        itemVector: FloatArray,
    ) {
        mutexFor(userId).withLock {
            val current = cache.getIfPresent(userId) ?: coldStart(userId)
            val updated = Scoring.applyEma(current, event, itemVector)
            cache.put(userId, updated)
            dirtySet.add(userId)
        }
    }

    fun snapshotDirty(): Map<Long, UserProfile> {
        val snapshot = mutableSetOf<Long>()
        val iter = dirtySet.iterator()
        while (iter.hasNext()) {
            snapshot.add(iter.next())
            iter.remove()
        }
        return snapshot
            .mapNotNull { id ->
                cache.getIfPresent(id)?.let { id to it }
            }.toMap()
    }

    private fun mutexFor(userId: Long): Mutex = mutexes.computeIfAbsent(userId) { Mutex() }

    companion object {
        const val DEFAULT_CAPACITY = 10_000L

        private fun coldStart(userId: Long): UserProfile =
            UserProfile(
                userId = userId,
                embeddingVersion = EmbeddingVersion("default"),
                positivePrototypes = emptyList(),
                negativePrototypes = emptyList(),
                sessionVector = FloatArray(Prototype.VECTOR_DIM),
                longTermVector = FloatArray(Prototype.VECTOR_DIM),
                lastAppliedEventId = 0L,
            )
    }
}
