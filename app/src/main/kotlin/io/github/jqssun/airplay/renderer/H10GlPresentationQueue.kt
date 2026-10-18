package io.github.jqssun.airplay.renderer

import java.util.TreeMap

/** Keeps media PTS identity separate from the monotonic audio/display deadline. */
class H10GlPresentationQueue(private val limit: Int = 64) {
    private val targets = TreeMap<Long, Long>()
    private var pendingDelivery: Long? = null
    @Synchronized fun hasPendingDelivery(): Boolean = pendingDelivery != null
    @Synchronized fun expirePendingDelivery(nowNs: Long, maximumAgeNs: Long): Boolean {
        val pending = pendingDelivery ?: return false
        if (nowNs - pending < maximumAgeNs) return false
        // Keep its metadata: a delayed callback may still carry this exact image.
        pendingDelivery = null
        return true
    }
    @Synchronized fun put(textureNs: Long, targetNs: Long) {
        targets[textureNs] = targetNs
        pendingDelivery = textureNs
        while (targets.size > limit) targets.pollFirstEntry()
    }
    @Synchronized fun take(textureNs: Long): Long? {
        val target = targets.remove(textureNs)
        if (target != null && pendingDelivery == textureNs) pendingDelivery = null
        // SurfaceTexture can acquire the latest image and skip older buffers.
        // Retire their metadata without applying a different frame's deadline.
        targets.headMap(textureNs, true).clear()
        return target
    }
    @Synchronized fun clear() { targets.clear(); pendingDelivery = null }
    @Synchronized fun size(): Int = targets.size
}
