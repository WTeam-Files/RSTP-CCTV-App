package com.zektopic.cctvapp

/**
 * Caps how often the app may restart itself to recover. A restart that does not fix the
 * problem, repeated forever, turns a dead camera into a battery-draining crash loop; after
 * [maxRestarts] within [windowMs] the watchdog backs off to slow retries instead.
 */
class RestartBudget(private val maxRestarts: Int = 6, private val windowMs: Long = 60 * 60_000L) {
    private val times = ArrayList<Long>()

    fun load(stamps: List<Long>) {
        times.clear(); times.addAll(stamps)
    }

    fun stamps(): List<Long> = times.toList()

    fun allow(nowMs: Long): Boolean {
        times.removeAll { nowMs - it > windowMs }
        return times.size < maxRestarts
    }

    fun record(nowMs: Long) {
        times.add(nowMs)
    }
}
