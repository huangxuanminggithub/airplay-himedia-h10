package io.github.jqssun.airplay.renderer

import org.junit.Assert.*
import org.junit.Test

class H10GlPresentationQueueTest {
    @Test fun lostAcquisitionCannotStallDecoderAndDelayedImageKeepsItsDeadline() {
        val q = H10GlPresentationQueue()
        q.put(1_000_000_000L, 2_000_000_000L)
        assertFalse(q.expirePendingDelivery(1_079_999_999L, 80_000_000L))
        assertTrue(q.hasPendingDelivery())
        assertTrue(q.expirePendingDelivery(1_080_000_000L, 80_000_000L))
        assertFalse(q.hasPendingDelivery())
        assertEquals(2_000_000_000L, q.take(1_000_000_000L))
    }
    @Test fun newDeliveryWaitsOnlyForAcquisitionAndRetainsAudioDeadline() {
        val q = H10GlPresentationQueue()
        q.put(100L, 200000000L)
        assertTrue(q.hasPendingDelivery())
        assertEquals(200000000L, q.take(100L))
        assertFalse(q.hasPendingDelivery())
    }
    @Test fun staleCallbackCannotFreeTheNextDelivery() {
        val q = H10GlPresentationQueue()
        q.put(100L, 1000L)
        assertEquals(1000L, q.take(100L))
        q.put(200L, 2000L)
        assertNull(q.take(100L))
        assertTrue(q.hasPendingDelivery())
        assertEquals(2000L, q.take(200L))
        assertFalse(q.hasPendingDelivery())
    }
    @Test fun sessionResetClearsAcquisitionWaitAndOldDeadline() {
        val q = H10GlPresentationQueue()
        q.put(100L, 1000L)
        q.clear()
        assertFalse(q.hasPendingDelivery())
        assertNull(q.take(100L))
    }
    @Test fun exactSourceIdentityKeepsOriginalAudioDeadline() {
        val q = H10GlPresentationQueue()
        q.put(1_700_000_000_000_000_000L, 42_140_000_000L)
        assertEquals(42_140_000_000L, q.take(1_700_000_000_000_000_000L))
    }
    @Test fun latestTextureRetiresCoalescedMetadataWithoutShiftingDeadline() {
        val q = H10GlPresentationQueue()
        q.put(100L, 1000L); q.put(200L, 2000L); q.put(300L, 3000L)
        assertEquals(2000L, q.take(200L))
        assertNull(q.take(100L))
        assertEquals(3000L, q.take(300L))
    }
    @Test fun missingIdentityDoesNotGuessAnotherFramesTimestamp() {
        val q = H10GlPresentationQueue()
        q.put(100L, 1000L)
        assertNull(q.take(99L))
        assertEquals(1000L, q.take(100L))
    }
    @Test fun stalledConsumerCannotGrowMetadataWithoutBound() {
        val q = H10GlPresentationQueue(4)
        repeat(1000) { q.put(it.toLong(), it * 1000L) }
        assertEquals(4, q.size())
        assertNull(q.take(995L))
        assertEquals(996000L, q.take(996L))
        q.clear(); assertEquals(0, q.size())
    }
}
