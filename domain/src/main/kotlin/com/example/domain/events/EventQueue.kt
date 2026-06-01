package com.example.domain.events

fun interface EventQueue {
    suspend fun enqueue(event: RawEvent)
}
