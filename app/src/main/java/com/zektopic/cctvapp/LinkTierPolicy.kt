package com.zektopic.cctvapp

/**
 * Chooses a video quality tier from what the network is doing.
 *
 * The phone's radio is 2.4 GHz only, so a weak or crowded link is the normal failure, and
 * the right response is to send less rather than to stall. Tiers step down quickly when the
 * sender reports backpressure and climb back slowly, so a marginal link settles on a rate it
 * can hold instead of oscillating. Pure Kotlin: the service samples the link every two
 * seconds and applies the result.
 */
class LinkTierPolicy(
    private val severeSamplesToDrop: Int = 2,
    private val moderateSamplesToDrop: Int = 4,
    private val okSamplesToRaise: Int = 60,
    private val okSamplesToRaiseAfterDrop: Int = 150,
    private val recentDropWindowMs: Long = 10 * 60_000L,
    private val cooldownMs: Long = 10_000L
) {
    data class Tier(val index: Int, val kbps: Int, val fps: Int)

    /**
     * @param congested20 sender cache is at least 20% full
     * @param congested60 sender cache is at least 60% full (frames are about to be dropped)
     * @param viewerOverflow a browser client was dropped for not keeping up
     * @param rssiDbm Wi-Fi signal, or null when unknown
     * @param linkMbps negotiated Wi-Fi rate, or null/<=0 when unknown
     */
    data class Sample(
        val congested20: Boolean = false,
        val congested60: Boolean = false,
        val viewerOverflow: Boolean = false,
        val rssiDbm: Int? = null,
        val linkMbps: Int? = null
    )

    enum class Pressure { OK, HOLD, MODERATE, SEVERE }

    companion object {
        val TIERS = listOf(
            Tier(0, 4000, 30),
            Tier(1, 2500, 30),
            Tier(2, 1500, 20),
            Tier(3, 800, 15),
            Tier(4, 500, 10)
        )

        fun pressureOf(s: Sample): Pressure {
            val link = s.linkMbps?.takeIf { it > 0 }
            val rssi = s.rssiDbm?.takeIf { it < 0 }
            if (s.congested60 || s.viewerOverflow) return Pressure.SEVERE
            if ((rssi != null && rssi < -88) || (link != null && link < 3)) return Pressure.SEVERE
            if (s.congested20) return Pressure.MODERATE
            // A weak radio on its own is not proof the stream is failing, but it is not a
            // reason to climb either.
            if ((rssi != null && rssi < -80) || (link != null && link < 12)) return Pressure.HOLD
            return Pressure.OK
        }
    }

    var tier: Int = 0
        private set

    private var severeRun = 0
    private var moderateRun = 0
    private var okRun = 0
    private var lastChangeMs = Long.MIN_VALUE / 2
    private var lastDropMs = Long.MIN_VALUE / 2

    /** Feeds one sample; returns true when the tier changed. */
    fun onSample(nowMs: Long, sample: Sample): Boolean {
        when (pressureOf(sample)) {
            Pressure.SEVERE -> { severeRun++; moderateRun = 0; okRun = 0 }
            Pressure.MODERATE -> { moderateRun++; severeRun = 0; okRun = 0 }
            Pressure.HOLD -> { severeRun = 0; moderateRun = 0; okRun = 0 }
            Pressure.OK -> { okRun++; severeRun = 0; moderateRun = 0 }
        }
        if (nowMs - lastChangeMs < cooldownMs) return false

        val drop = severeRun >= severeSamplesToDrop || moderateRun >= moderateSamplesToDrop
        if (drop && tier < TIERS.size - 1) {
            tier++
            lastChangeMs = nowMs
            lastDropMs = nowMs
            severeRun = 0; moderateRun = 0; okRun = 0
            return true
        }
        val needed = if (nowMs - lastDropMs < recentDropWindowMs) okSamplesToRaiseAfterDrop else okSamplesToRaise
        if (okRun >= needed && tier > 0) {
            tier--
            lastChangeMs = nowMs
            okRun = 0
            return true
        }
        return false
    }

    fun reset() {
        tier = 0; severeRun = 0; moderateRun = 0; okRun = 0
    }

    fun current(): Tier = TIERS[tier]
}
