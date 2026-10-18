package io.github.jqssun.airplay.renderer

import org.junit.Assert.*
import org.junit.Test

class H10GlStagingQueueTest {
    @Test fun futureFrameWaitsAndKeepsItsAudioDeadline() {
        val q = H10GlStagingQueue(2); val s = q.reserve(); q.commit(s, 100L)
        assertNull(q.takeDue(90L, 5L)); assertEquals(95L, q.wakeupNs(5L))
        assertEquals(H10GlStagingQueue.Frame(s, 100L), q.takeDue(95L, 5L))
    }
    @Test fun overflowReusesOldestWithoutGrowingLatency() {
        val q = H10GlStagingQueue(2); val a=q.reserve(); q.commit(a, 100)
        val b=q.reserve(); q.commit(b, 200)
        assertEquals(a,q.reserve()); q.commit(a,300)
        assertEquals(1L,q.dropped); assertEquals(2,q.size())
        assertEquals(b,q.takeDue(200,0)?.slot)
    }
    @Test fun overdueFramesChooseLatestAndRecycleOlderSlots() {
        val q=H10GlStagingQueue(3)
        repeat(3) { q.commit(q.reserve(), (it+1)*100L) }
        val f=q.takeDue(300,0)!!
        assertEquals(300L,f.targetNs); assertEquals(2L,q.dropped)
        q.release(f.slot)
        assertEquals(setOf(0,1,2),setOf(q.reserve(),q.reserve(),q.reserve()))
    }
    @Test fun orderIsPreservedWhenClockEstimateMovesBackwards() {
        val q=H10GlStagingQueue(2); q.commit(q.reserve(),200); q.commit(q.reserve(),100)
        assertNull(q.takeDue(150,0)); assertEquals(100L,q.takeDue(200,0)?.targetNs)
    }
    @Test fun sessionResetDiscardsOldDeadlinesAndRestoresPool() {
        val q=H10GlStagingQueue(2); q.commit(q.reserve(),500); q.reset()
        assertNull(q.wakeupNs(0)); assertEquals(0,q.size()); assertEquals(0L,q.dropped)
        assertEquals(setOf(0,1),setOf(q.reserve(),q.reserve()))
    }
    @Test fun aThirtyFpsSourceIsNotRepeatedOrInterpolated() {
        val q=H10GlStagingQueue(2); q.commit(q.reserve(),33_333_333)
        assertNotNull(q.takeDue(33_333_333,0)); assertNull(q.takeDue(50_000_000,0))
    }
    @Test fun boundedLeadDoesNotAlterStoredPresentationTime() {
        val q=H10GlStagingQueue(1); q.commit(q.reserve(),80_000_000)
        assertNull(q.takeDue(73_000_000,6_000_000))
        assertEquals(80_000_000L,q.takeDue(74_000_000,6_000_000)?.targetNs)
    }
    @Test fun invalidCapacityFailsFast() {
        try { H10GlStagingQueue(0); fail("zero capacity") } catch (_: IllegalArgumentException) {}
    }
}
