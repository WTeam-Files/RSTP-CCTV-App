package com.zektopic.cctvapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartRetryPolicyTest {

    @Test
    fun `delays grow then hold at the last value`() {
        val p = StartRetryPolicy(longArrayOf(1, 2, 3), restartProcessAfter = 100)
        assertEquals(listOf(1L, 2L, 3L, 3L, 3L), (1..5).map { p.onFailure().delayMs })
    }

    @Test
    fun `asks for a fresh process only after enough consecutive failures`() {
        val p = StartRetryPolicy(longArrayOf(1), restartProcessAfter = 3)
        assertFalse(p.onFailure().restartProcess)
        assertFalse(p.onFailure().restartProcess)
        assertTrue(p.onFailure().restartProcess)
    }

    @Test
    fun `reset starts the schedule over`() {
        val p = StartRetryPolicy(longArrayOf(10, 20), restartProcessAfter = 2)
        p.onFailure()
        p.reset()
        assertEquals(0, p.consecutiveFailures)
        val d = p.onFailure()
        assertEquals(10L, d.delayMs)
        assertFalse(d.restartProcess)
    }

    @Test
    fun `default schedule backs off to a minute`() {
        val p = StartRetryPolicy()
        val delays = (1..8).map { p.onFailure().delayMs }
        assertEquals(2_000L, delays.first())
        assertEquals(60_000L, delays.last())
    }
}
