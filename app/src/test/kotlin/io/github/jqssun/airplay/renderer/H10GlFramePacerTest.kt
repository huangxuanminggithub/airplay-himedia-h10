package io.github.jqssun.airplay.renderer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class H10GlFramePacerTest {
    @Test fun steadySixtyFpsKeepsOneSmallLead() {
        val p = H10GlFramePacer()
        val start = 1_000_000_000L
        repeat(120) { i ->
            val now = start + i * 16_667_000L
            assertEquals(now + 8_000_000L, p.target(i * 16_667L, now - 120_000_000L, now))
        }
    }
    @Test fun packetBurstDoesNotCreateUnboundedLatency() {
        val p = H10GlFramePacer()
        val now = 1_000_000_000L
        val targets = (0..20).map { p.target(it * 16_667L, now - 100_000_000L, now) }
        assertTrue(targets.all { it in (now + 2_000_000L)..(now + 25_000_000L) })
        assertTrue(targets[1] > targets[0])
    }
    @Test fun futureAudioDeadlineIsUnchanged() {
        val p = H10GlFramePacer()
        val now = 1_000_000_000L
        assertEquals(now + 140_000_000L, p.target(0L, now + 140_000_000L, now))
    }
    @Test fun sourceDiscontinuityStartsFreshLead() {
        val p = H10GlFramePacer()
        val now = 1_000_000_000L
        p.target(0L, now, now)
        assertEquals(now + 8_000_000L, p.target(1_000_000L, now - 1, now))
    }
    @Test fun resetDoesNotReusePriorSessionDeadline() {
        val p = H10GlFramePacer()
        val now = 1_000_000_000L
        p.target(0L, now + 140_000_000L, now)
        p.reset()
        assertEquals(now + 8_000_000L, p.target(16_667L, now, now))
    }
    @Test fun backwardsSourceTimestampStartsFreshLead() {
        val p = H10GlFramePacer()
        val now = 1_000_000_000L
        p.target(100_000L, now, now)
        assertEquals(now + 8_000_000L, p.target(0L, now, now))
    }
}
