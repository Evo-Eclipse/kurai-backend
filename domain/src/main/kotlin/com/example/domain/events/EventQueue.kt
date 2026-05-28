package com.example.domain.events

import com.example.domain.model.UserEvent

fun interface EventQueue {
    suspend fun enqueue(event: UserEvent)
}
