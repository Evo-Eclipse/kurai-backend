package com.example.domain.profile

import com.example.domain.model.UserEvent
import com.example.domain.model.UserProfile

typealias ProfileLoadPort = suspend (userId: Long) -> UserProfile?

typealias ProfileSavePort = (profile: UserProfile) -> Unit

// Returns events with their pre-fetched item vectors for replay.
typealias EventLoadPort = suspend (userId: Long, sinceEventId: Long) -> List<Pair<UserEvent, FloatArray>>
