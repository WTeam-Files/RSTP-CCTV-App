package com.zektopic.cctvapp

/**
 * Decides how long to wait before bringing the camera stream up again after it failed.
 *
 * The camera can legitimately be unavailable for a while after boot (the service starts
 * before the user has unlocked, the camera service is still re-enumerating, a policy check
 * fails transiently), so a single failed attempt must not be final. Delays grow and then
 * hold at the last value. After [restartProcessAfter] consecutive failures it also asks for
 * a fresh process, because a process that was started that early has been seen to keep
 * failing while a new one opens the camera at once.
 *
 * Pure Kotlin so the schedule can be unit tested; the service owns the timers.
 */
class StartRetryPolicy(
    private val delaysMs: LongArray = DEFAULT_DELAYS_MS,
    private val restartProcessAfter: Int = DEFAULT_RESTART_AFTER
) {
    companion object {
        val DEFAULT_DELAYS_MS = longArrayOf(2_000, 5_000, 10_000, 20_000, 30_000, 60_000)
        const val DEFAULT_RESTART_AFTER = 5
    }

    data class Decision(val delayMs: Long, val restartProcess: Boolean)

    var consecutiveFailures = 0
        private set

    fun onFailure(): Decision {
        consecutiveFailures++
        val delay = delaysMs[minOf(consecutiveFailures - 1, delaysMs.size - 1)]
        return Decision(delay, consecutiveFailures >= restartProcessAfter)
    }

    /** A frame arrived, or the user unlocked: start the schedule over. */
    fun reset() {
        consecutiveFailures = 0
    }
}
