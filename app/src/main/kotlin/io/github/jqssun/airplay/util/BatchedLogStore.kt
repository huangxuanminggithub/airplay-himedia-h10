package io.github.jqssun.airplay.util

import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Audio/video callbacks append only; snapshot construction and disk I/O run on one worker. */
class BatchedLogStore(
    initial: List<String> = emptyList(),
    private val capacity: Int = 9999,
    private val intervalMs: Long = 250L,
    private val persist: (List<String>) -> Unit,
    // Must only publish an immutable snapshot (StateFlow setter), never perform I/O.
    private val publish: (List<String>) -> Unit,
) : AutoCloseable {
    private val lock = Any()
    private val entries = ArrayDeque<String>()
    private val worker = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "AirPlayLogWriter").apply { isDaemon = true }
    }
    private var scheduled: ScheduledFuture<*>? = null
    private var epoch = 0L
    private var closed = false
    init {
        require(capacity > 0 && intervalMs >= 0)
        initial.takeLast(capacity).forEach { entries.addLast(it) }
        publish(entries.toList())
    }
    fun add(line: String) = synchronized(lock) {
        if (closed) return@synchronized
        entries.addLast(line)
        while (entries.size > capacity) entries.removeFirst()
        if (scheduled == null) scheduled = worker.schedule({ flush() }, intervalMs, TimeUnit.MILLISECONDS)
    }
    fun snapshot(): List<String> = synchronized(lock) { entries.toList() }
    fun clear() = synchronized(lock) {
        if (closed) return@synchronized
        epoch++
        entries.clear()
        publish(emptyList())
        // Serialized after any already-running old write; it cannot resurrect cleared logs.
        worker.execute { runCatching { persist(emptyList()) } }
    }
    private fun flush() {
        val data = synchronized(lock) {
            scheduled = null
            Pair(epoch, entries.toList())
        }
        runCatching { persist(data.second) } // never hold the producer monitor across disk I/O
        synchronized(lock) {
            if (epoch == data.first) publish(data.second)
        }
    }
    override fun close() = synchronized(lock) {
        if (closed) return@synchronized
        closed = true
        scheduled?.cancel(false)
        scheduled = null
        worker.execute { flush() }
        worker.shutdown()
    }
    fun awaitClosed(timeoutMs: Long): Boolean = worker.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)
}
