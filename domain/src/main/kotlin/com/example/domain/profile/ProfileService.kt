package com.example.domain.profile

import com.example.domain.model.UserEvent
import com.example.domain.model.UserProfile

class ProfileService(
    private val loadProfile: ProfileLoadPort,
    private val loadEvents: EventLoadPort,
    private val saveProfile: ProfileSavePort,
) {
    fun getOrLoad(userId: Long): UserProfile {
        val base = loadProfile(userId) ?: UserProfile.coldStart(userId)
        val events = loadEvents(userId, base.lastAppliedEventId)
        return events.fold(base) { acc, (ev, vec) -> Scoring.applyEma(acc, ev, vec) }
    }

    fun update(
        userId: Long,
        event: UserEvent,
        itemVector: FloatArray,
    ) {
        val current = loadProfile(userId) ?: UserProfile.coldStart(userId)
        val updated = Scoring.applyEma(current, event, itemVector)
        saveProfile(updated)
    }
}
