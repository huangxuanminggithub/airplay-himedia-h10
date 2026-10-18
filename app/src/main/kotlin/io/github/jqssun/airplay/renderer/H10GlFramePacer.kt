package io.github.jqssun.airplay.renderer

/** Preserves audio's future deadlines; only smooths frames whose deadline already passed. */
class H10GlFramePacer {
    private var lastPtsUs = Long.MIN_VALUE
    private var lastTargetNs = 0L

    fun target(ptsUs: Long, audioTargetNs: Long, nowNs: Long): Long {
        val deltaUs = if (lastPtsUs == Long.MIN_VALUE) 0L else ptsUs - lastPtsUs
        val value = if (audioTargetNs > nowNs) audioTargetNs
        else if (lastTargetNs > 0L && deltaUs in 1..100_000L)
            (lastTargetNs + deltaUs * 1000L).coerceIn(nowNs + 2_000_000L, nowNs + 25_000_000L)
        else nowNs + 8_000_000L
        lastPtsUs = ptsUs
        lastTargetNs = value
        return value
    }

    fun reset() { lastPtsUs = Long.MIN_VALUE; lastTargetNs = 0L }
}
