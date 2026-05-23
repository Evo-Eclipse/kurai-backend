package com.example

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReadinessGateTest {
    @Test
    fun `gate starts not ready`() {
        assertFalse(ReadinessGate().isReady())
    }

    @Test
    fun `gate becomes ready after markReady`() {
        val gate = ReadinessGate()
        gate.markReady()
        assertTrue(gate.isReady())
    }
}
