package io.github.jqssun.airplay.util

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList

class BatchedLogStoreTest {
    @Test fun blockedDiskDoesNotBlockAudioVideoLogProducers() {
        val entered=CountDownLatch(1); val release=CountDownLatch(1)
        val store=BatchedLogStore(intervalMs=0, persist={ entered.countDown(); release.await(3,TimeUnit.SECONDS) }, publish={})
        val producers=Executors.newSingleThreadExecutor()
        try {
            store.add("first"); assertTrue(entered.await(2,TimeUnit.SECONDS))
            val done=producers.submit { repeat(500) { store.add("frame $it") } }
            done.get(500,TimeUnit.MILLISECONDS)
            assertEquals(501,store.snapshot().size)
        } finally { release.countDown(); store.close(); assertTrue(store.awaitClosed(3000)); producers.shutdownNow() }
    }
    @Test fun bufferRetainsOnlyLatestEntries() {
        val store=BatchedLogStore(initial=listOf("old"), capacity=3, intervalMs=60_000, persist={}, publish={})
        repeat(5) { store.add("$it") }
        assertEquals(listOf("2","3","4"),store.snapshot())
        store.close(); assertTrue(store.awaitClosed(2000))
    }
    @Test fun clearCannotBeUndoneByAnInFlightOldWrite() {
        val entered=CountDownLatch(1); val release=CountDownLatch(1)
        val disk=CopyOnWriteArrayList<List<String>>(); val ui=CopyOnWriteArrayList<List<String>>()
        val store=BatchedLogStore(intervalMs=0, persist={
            if (it.isNotEmpty()) { entered.countDown(); release.await(3,TimeUnit.SECONDS) }
            disk.add(it)
        }, publish={ ui.add(it) })
        try { store.add("old"); assertTrue(entered.await(2000,TimeUnit.MILLISECONDS)); store.clear() }
        finally { release.countDown(); store.close(); assertTrue(store.awaitClosed(3000)) }
        assertEquals(emptyList<String>(),disk.last()); assertEquals(emptyList<String>(),ui.last())
    }
    @Test fun closeFlushesTheNewestUnpublishedBatch() {
        val disk=CopyOnWriteArrayList<List<String>>()
        val store=BatchedLogStore(intervalMs=60_000, persist={disk.add(it)},publish={})
        store.add("a"); store.add("b"); store.close()
        assertTrue(store.awaitClosed(2000)); assertEquals(listOf("a","b"),disk.last())
    }
    @Test fun diskFailureDoesNotKillTheWorkerOrLoseInMemoryLogs() {
        val ui=CopyOnWriteArrayList<List<String>>()
        val store=BatchedLogStore(intervalMs=60_000, persist={error("disk full")}, publish={ui.add(it)})
        store.add("retained"); store.close(); assertTrue(store.awaitClosed(2000))
        assertEquals(listOf("retained"),ui.last())
    }
    @Test fun appendAfterClearPersistsOnlyTheNewSession() {
        val disk=CopyOnWriteArrayList<List<String>>()
        val store=BatchedLogStore(intervalMs=60_000,persist={disk.add(it)},publish={})
        store.add("old"); store.clear(); store.add("new"); store.close()
        assertTrue(store.awaitClosed(2000)); assertEquals(listOf("new"),disk.last())
    }
}
