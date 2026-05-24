package com.example

class ReadinessGate {
    @Volatile private var ready = false

    fun markReady() {
        ready = true
    }

    fun isReady(): Boolean = ready
}
