package io.github.jqssun.airplay.renderer

import java.util.ArrayDeque

/** GL-thread-owned, bounded image slots. Deadlines retain the accepted audio clock. */
class H10GlStagingQueue(val capacity: Int = 16) {
    data class Frame(val slot: Int, val targetNs: Long)
    private val free = ArrayDeque<Int>()
    private val pending = ArrayDeque<Frame>()
    var dropped = 0L; private set
    init { require(capacity > 0); reset() }
    fun reserve(): Int = if (free.isNotEmpty()) free.removeFirst() else {
        dropped++
        pending.removeFirst().slot
    }
    fun commit(slot: Int, targetNs: Long) { pending.addLast(Frame(slot, targetNs)) }
    fun release(slot: Int) { free.addLast(slot) }
    fun size(): Int = pending.size
    fun wakeupNs(leadNs: Long): Long? = pending.peekFirst()?.targetNs?.minus(leadNs)
    fun takeDue(nowNs: Long, leadNs: Long): Frame? {
        var due: Frame? = null
        while (pending.isNotEmpty() && pending.peekFirst().targetNs <= nowNs + leadNs) {
            due?.let { release(it.slot); dropped++ }
            due = pending.removeFirst()
        }
        return due
    }
    fun reset() {
        pending.clear(); free.clear(); dropped = 0L
        repeat(capacity) { free.addLast(it) }
    }
}
