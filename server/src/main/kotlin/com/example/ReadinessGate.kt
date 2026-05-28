package com.example

class ReadinessGate {
    @Volatile private var ready = false

    fun markReady() {
        ready = true
    }

    fun markStopping() {
        ready = false
    }

    fun isReady(): Boolean = ready
}
