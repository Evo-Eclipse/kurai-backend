package com.example.application.profile

import com.example.domain.model.UserEvent
import com.example.domain.model.UserProfile
import com.example.domain.profile.EventLoadPort
import com.example.domain.profile.ProfileLoadPort
import com.example.domain.profile.Scoring
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

class CachingProfileAdapter(
    private val loadProfile: ProfileLoadPort,
    private val loadEvents: EventLoadPort,
    cacheCapacity: Long = DEFAULT_CAPACITY,
) {
    private val cache: Cache<Long, UserProfile> =
        Caffeine.newBuilder().maximumSize(cacheCapacity).build()
    private val mutexes = ConcurrentHashMap<Long, Mutex>()
    private val dirtyMap = ConcurrentHashMap<Long, UserProfile>()

    suspend fun getOrLoad(userId: Long): UserProfile =
        mutexFor(userId).withLock {
            cache.getIfPresent(userId)?.let { return it }
            val base = loadProfile(userId) ?: UserProfile.coldStart(userId)
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
            val current = cache.getIfPresent(userId) ?: UserProfile.coldStart(userId)
            val updated = Scoring.applyEma(current, event, itemVector)
            cache.put(userId, updated)
            dirtyMap[userId] = updated
        }
    }

    fun snapshotDirty(): Map<Long, UserProfile> {
        val drained = mutableMapOf<Long, UserProfile>()
        val iter = dirtyMap.entries.iterator()
        while (iter.hasNext()) {
            val (id, profile) = iter.next()
            drained[id] = profile
            iter.remove()
        }
        return drained
    }

    fun cachedUserIds(): Set<Long> = cache.asMap().keys.toSet()

    suspend fun forceUpdate(
        userId: Long,
        profile: UserProfile,
    ) {
        mutexFor(userId).withLock {
            cache.put(userId, profile)
        }
    }

    private fun mutexFor(userId: Long): Mutex = mutexes.computeIfAbsent(userId) { Mutex() }

    companion object {
        const val DEFAULT_CAPACITY = 10_000L
    }
}
