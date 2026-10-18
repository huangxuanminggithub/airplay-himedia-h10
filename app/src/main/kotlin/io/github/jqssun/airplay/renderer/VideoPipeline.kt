package io.github.jqssun.airplay.renderer

import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.HandlerThread
import io.github.jqssun.airplay.BuildConfig
import java.util.concurrent.atomic.AtomicLong
import android.opengl.EGL14
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

// decodes into an app-owned SurfaceTexture that outlives display surface
// gl thread blits it to any surface attached, so fullscreen toggles re-point display without restarting codec
class VideoPipeline {

    private val lock = Object()
    private var thread: Thread? = null
    @Volatile private var running = false

    private var egl: EglCore? = null
    private var window: EGLSurface = EGL14.EGL_NO_SURFACE
    private var winW = 0
    private var winH = 0

    private var oesTex = 0
    private var program = 0
    private var aPos = 0
    private var aTex = 0
    private var uTexMatrix = 0
    private val texMatrix = FloatArray(16)
    private var hasFrame = false
    private var framePresentationNs = 0L
    private val presentationQueue = H10GlPresentationQueue()
    @Volatile var useSourceTimestampMapping = BuildConfig.H10_GL60_OPT; private set
    @Volatile private var gateDecoderDelivery = false
    private var mappedFrames = 0L
    fun hasPendingDecoderDelivery(): Boolean {
        if (!gateDecoderDelivery || !useSourceTimestampMapping) return false
        if (presentationQueue.expirePendingDelivery(System.nanoTime(), 80_000_000L)) {
            // A lost vendor callback must not stall the whole decoder. Keep V5's
            // timestamp mapping and switch back to its ungated immediate delivery.
            gateDecoderDelivery = false
            Log.w(TAG, "H10_GL_MAP acquisition timed out; reverting to accepted V5 delivery")
            return false
        }
        return presentationQueue.hasPendingDelivery()
    }
    fun mapDecoderPresentation(textureNs: Long, targetNs: Long) {
        presentationQueue.put(textureNs, targetNs)
    }
    fun resetDecoderPresentation() {
        presentationQueue.clear()
        useSourceTimestampMapping = BuildConfig.H10_GL60_OPT
        gateDecoderDelivery = false
        mappedFrames = 0L
    }
    private fun resolvePresentation(textureNs: Long): Long {
        if (!useSourceTimestampMapping) return textureNs
        val target = presentationQueue.take(textureNs)
        if (target != null) {
            if (++mappedFrames == 1L || mappedFrames % 120L == 0L)
                Log.i(TAG, "H10_GL_MAP matched=$mappedFrames pending=${presentationQueue.size()}")
            return target
        }
        // Some vendor codecs violate the default PTS timestamp contract. Restore
        // the accepted timed-release path instead of losing the audio clock.
        useSourceTimestampMapping = false
        presentationQueue.clear()
        Log.w(TAG, "H10_GL_MAP timestamp mismatch; reverting to accepted timed release")
        return textureNs
    }
    private var staged = H10GlStagingQueue()
    private val stageTextures = IntArray(staged.capacity)
    private val stageFbos = IntArray(staged.capacity)
    private val identityMatrix = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }
    private var stageProgram = 0
    private var stagePos = 0
    private var stageTex = 0
    private var stageMatrix = 0
    private var stageW = 0
    private var stageH = 0
    private var stagingEnabled = BuildConfig.H10_GL60_OPT
    private var stageReportStartNs = 0L
    private var stagePresented = 0L
    private var stageReportPresented = 0L
    private var stageCopySumNs = 0L
    private var stageCopyMaxNs = 0L
    private var stageCopyCount = 0L
    private var stageDrawSumNs = 0L
    private var stageDrawMaxNs = 0L
    private var stageDrawCount = 0L
    private var _displayFrames = 0L
    private val frameThread = if (BuildConfig.H10_GL60_OPT)
        HandlerThread("H10GLFrames", android.os.Process.THREAD_PRIORITY_DISPLAY).apply { start() } else null
    private val frameHandler = frameThread?.let { Handler(it.looper) }
    private val callbackCount = AtomicLong()
    private val coalescedCount = AtomicLong()
    private var uniqueFrames = 0L
    private var duplicateFrames = 0L
    private var lastTextureNs = Long.MIN_VALUE
    private var reportStartNs = 0L
    private var reportUnique = 0L
    private var reportCallbacks = 0L
    private var updateSumNs = 0L
    private var updateMaxNs = 0L
    private var swapSumNs = 0L
    private var swapMaxNs = 0L
    private var sampleFrames = 0L
    private var textureStepMaxNs = 0L

    private var surfaceTexture: SurfaceTexture? = null
    var inputSurface: Surface? = null; private set

    @Volatile private var frameAvailable = false
    private var pendingDisplay: Surface? = null
    private var displayDirty = false
    @Volatile private var videoW = 0
    @Volatile private var videoH = 0

    fun start() = synchronized(lock) {
        if (running) return@synchronized
        running = true
        thread = Thread({ _loop() }, "VideoPipeline").also { it.start() }
        while (inputSurface == null && running) lock.wait()
    }

    fun setDisplaySurface(surface: Surface?) = synchronized(lock) {
        pendingDisplay = surface
        displayDirty = true
        lock.notifyAll()
    }

    fun setVideoSize(w: Int, h: Int) {
        videoW = w
        videoH = h
        surfaceTexture?.setDefaultBufferSize(w, h)
    }

    fun release() {
        synchronized(lock) {
            if (!running) return
            running = false
            lock.notifyAll()
        }
        thread?.join()
        thread = null
        frameThread?.quitSafely()
    }

    private fun _loop() {
        if (BuildConfig.H10_GL60_OPT)
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY)
        try {
            egl = EglCore()
            Log.i(TAG, "MVP_SYNC EGL presentation time supported=${egl?.supportsPresentationTime}")
            _initGl()
        } catch (e: Exception) {
            Log.e(TAG, "GL init failed", e)
            synchronized(lock) { running = false; lock.notifyAll() }
            return
        }
        Matrix.setIdentityM(texMatrix, 0)
        synchronized(lock) {
            inputSurface = Surface(surfaceTexture)
            lock.notifyAll()
        }
        while (true) {
            var newDisplay: Surface? = null
            var displayChanged = false
            var doFrame = false
            synchronized(lock) {
                while (running && !frameAvailable && !displayDirty) {
                    val wake = if (stagingEnabled && window != EGL14.EGL_NO_SURFACE)
                        staged.wakeupNs(STAGE_LEAD_NS) else null
                    if (wake == null) lock.wait() else {
                        val waitNs = wake - System.nanoTime()
                        if (waitNs <= 0L) break
                        lock.wait(waitNs / 1_000_000L, (waitNs % 1_000_000L).toInt())
                    }
                }
                if (running && displayDirty) {
                    newDisplay = pendingDisplay
                    displayChanged = true
                    displayDirty = false
                }
                if (running && frameAvailable) {
                    frameAvailable = false
                    doFrame = true
                }
            }
            if (!running) break
            if (displayChanged) _bindDisplay(newDisplay)
            if (doFrame) _consumeAndDraw()
            if (stagingEnabled) {
                try { _presentStaged() } catch (e: Exception) {
                    Log.e(TAG, "H10_STAGE display failed; reverting to accepted V6 GL path", e)
                    stagingEnabled = false
                    staged.reset()
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                    egl?.makeCurrent(window)
                    if (hasFrame && window != EGL14.EGL_NO_SURFACE) _render()
                }
            }
        }
        _releaseGl()
    }

    private fun _bindDisplay(surface: Surface?) {
        val egl = egl ?: return
        staged.reset()
        if (window != EGL14.EGL_NO_SURFACE) {
            egl.makeCurrent()
            egl.destroySurface(window)
            window = EGL14.EGL_NO_SURFACE
        }
        if (surface == null || !surface.isValid) return
        window = egl.windowSurface(surface)
        if (window == EGL14.EGL_NO_SURFACE) {
            Log.w(TAG, "eglCreateWindowSurface failed: ${EGL14.eglGetError()}")
            return
        }
        egl.makeCurrent(window)
        winW = egl.query(window, EGL14.EGL_WIDTH)
        winH = egl.query(window, EGL14.EGL_HEIGHT)
        // an idle source sends no new frames, so repaint last one or new surface stays black
        if (hasFrame) _render()
    }

    private fun _consumeAndDraw() {
        val st = surfaceTexture ?: return
        val egl = egl ?: return
        if (window == EGL14.EGL_NO_SURFACE) {
            // no display: keep consuming so decoder doesn't stall
            egl.makeCurrent()
            st.updateTexImage()
            if (BuildConfig.H10_GL60_OPT) resolvePresentation(st.timestamp)
            hasFrame = true
            return
        }
        if (stagingEnabled) egl.makeCurrent() else egl.makeCurrent(window)
        val beforeUpdate = System.nanoTime()
        st.updateTexImage()
        st.getTransformMatrix(texMatrix)
        val textureNs = st.timestamp
        if (BuildConfig.H10_GL60_OPT) {
            val cost = System.nanoTime() - beforeUpdate
            updateSumNs += cost
            updateMaxNs = maxOf(updateMaxNs, cost)
            if (lastTextureNs == textureNs) {
                duplicateFrames++
                return
            }
            if (lastTextureNs != Long.MIN_VALUE && textureNs > lastTextureNs)
                textureStepMaxNs = maxOf(textureStepMaxNs, textureNs - lastTextureNs)
            lastTextureNs = textureNs
            uniqueFrames++
            sampleFrames++
        }
        framePresentationNs = if (BuildConfig.H10_GL60_OPT) resolvePresentation(textureNs) else textureNs
        hasFrame = true
        if (stagingEnabled) {
            try { _stageImage() } catch (e: Exception) {
                Log.e(TAG, "H10_STAGE failed; reverting to accepted V6 GL path", e)
                stagingEnabled = false
                staged.reset()
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                egl.makeCurrent(window)
                _render()
            }
        } else _render()
    }

    private fun _stageImage() {
        val begin = System.nanoTime()
        val desiredW = minOf(videoW, winW)
        val desiredH = minOf(videoH, winH)
        if (stageW != desiredW || stageH != desiredH) {
            _deleteStageImages()
            stageW = desiredW; stageH = desiredH
            // Bound GPU allocation to 64 MiB, including landscape streams.
            val bytesPerImage = stageW.toLong() * stageH * 4
            val slots = (64L * 1024 * 1024 / bytesPerImage.coerceAtLeast(1)).coerceIn(2, 16).toInt()
            staged = H10GlStagingQueue(slots)
            Log.i(TAG, "H10_STAGE pool ${stageW}x${stageH} slots=$slots maxBytes=${bytesPerImage*slots}")
        }
        check(stageW > 0 && stageH > 0)
        val slot = staged.reserve()
        if (stageTextures[slot] == 0) {
            val one = IntArray(1)
            GLES20.glGenTextures(1, one, 0); stageTextures[slot] = one[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, one[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, stageW, stageH, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glGenFramebuffers(1, one, 0); stageFbos[slot] = one[0]
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, one[0])
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, stageTextures[slot], 0)
            check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE)
        } else GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, stageFbos[slot])
        _drawImage(stageW, stageH, program, aPos, aTex, uTexMatrix,
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex, texMatrix)
        GLES20.glFlush()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        val now = System.nanoTime()
        val target = if (framePresentationNs in (now - 500_000_000L)..(now + 300_000_000L))
            framePresentationNs else now
        staged.commit(slot, target)
        val cost = System.nanoTime() - begin
        stageCopySumNs += cost; stageCopyMaxNs = maxOf(stageCopyMaxNs, cost); stageCopyCount++
    }

    private fun _presentStaged() {
        if (window == EGL14.EGL_NO_SURFACE) return
        val frame = staged.takeDue(System.nanoTime(), STAGE_LEAD_NS) ?: return
        val begin = System.nanoTime()
        egl?.makeCurrent(window)
        _drawImage(winW, winH, stageProgram, stagePos, stageTex, stageMatrix,
            GLES20.GL_TEXTURE_2D, stageTextures[frame.slot], identityMatrix)
        // Retain the exact audio-clock deadline; only move already-late frames to NOW.
        val now = System.nanoTime()
        val target = frame.targetNs.coerceAtLeast(now)
        check(egl?.swap(window, target) == true) { "EGL staged swap failed" }
        staged.release(frame.slot)
        stagePresented++
        val cost = System.nanoTime() - begin
        stageDrawSumNs += cost; stageDrawMaxNs = maxOf(stageDrawMaxNs, cost); stageDrawCount++
        if (stageReportStartNs == 0L) { stageReportStartNs = now; stageReportPresented = stagePresented }
        val elapsed = now - stageReportStartNs
        if (elapsed >= 2_000_000_000L) {
            Log.i(TAG, "H10_STAGE presented=$stagePresented fps=${(stagePresented-stageReportPresented)*1e9/elapsed} " +
                "consumed=$uniqueFrames dropped=${staged.dropped} pending=${staged.size()} " +
                "copyMeanUs=${stageCopySumNs/stageCopyCount.coerceAtLeast(1)/1000} copyMaxUs=${stageCopyMaxNs/1000} " +
                "drawMeanUs=${stageDrawSumNs/stageDrawCount.coerceAtLeast(1)/1000} drawMaxUs=${stageDrawMaxNs/1000} " +
                "updateMaxUs=${updateMaxNs/1000} callbacks=${callbackCount.get()} holdUs=${(target-now)/1000}")
            stageReportStartNs = now; stageReportPresented = stagePresented
            stageCopySumNs = 0; stageCopyMaxNs = 0; stageCopyCount = 0
            stageDrawSumNs = 0; stageDrawMaxNs = 0; stageDrawCount = 0
            updateSumNs = 0; updateMaxNs = 0; sampleFrames = 0
        }
    }

    private fun _deleteStageImages() {
        GLES20.glDeleteFramebuffers(stageFbos.size, stageFbos, 0)
        GLES20.glDeleteTextures(stageTextures.size, stageTextures, 0)
        stageFbos.fill(0); stageTextures.fill(0); staged.reset()
    }

    private fun _drawImage(w: Int, h: Int, prog: Int, pos: Int, tex: Int, matrix: Int,
                           textureTarget: Int, texture: Int, transform: FloatArray) {
        GLES20.glViewport(0, 0, w, h)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(prog)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(textureTarget, texture)
        GLES20.glUniformMatrix4fv(matrix, 1, false, transform, 0)
        GLES20.glEnableVertexAttribArray(pos)
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, POS)
        GLES20.glEnableVertexAttribArray(tex)
        GLES20.glVertexAttribPointer(tex, 2, GLES20.GL_FLOAT, false, 0, TEX)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(pos); GLES20.glDisableVertexAttribArray(tex)
    }

    private fun _render() {
        GLES20.glViewport(0, 0, winW, winH)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, POS)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, TEX)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aTex)
        val now = System.nanoTime()
        // MediaCodec sets the SurfaceTexture timestamp, but the GL blit creates a
        // NEW buffer. Preserve the monotonic render time all the way to SurfaceFlinger.
        val target = if (framePresentationNs in (now - 500_000_000L)..(now + 300_000_000L))
            framePresentationNs.coerceAtLeast(now) else now
        val beforeSwap = System.nanoTime()
        egl?.swap(window, target)
        if (BuildConfig.H10_GL60_OPT) {
            val cost = System.nanoTime() - beforeSwap
            swapSumNs += cost
            swapMaxNs = maxOf(swapMaxNs, cost)
            val reportNow = System.nanoTime()
            if (reportStartNs == 0L) {
                reportStartNs = reportNow
                reportUnique = uniqueFrames
                reportCallbacks = callbackCount.get()
            }
            val elapsed = reportNow - reportStartNs
            if (elapsed >= 2_000_000_000L) {
                val count = sampleFrames.coerceAtLeast(1)
                val cb = callbackCount.get()
                Log.i(TAG, "H10_GL unique=$uniqueFrames fps=${(uniqueFrames-reportUnique)*1e9/elapsed} " +
                    "callbacks=${cb-reportCallbacks} coalesced=${coalescedCount.get()} duplicates=$duplicateFrames " +
                    "updateMeanUs=${updateSumNs/count/1000} updateMaxUs=${updateMaxNs/1000} " +
                    "swapMeanUs=${swapSumNs/count/1000} swapMaxUs=${swapMaxNs/1000} " +
                    "textureStepMaxUs=${textureStepMaxNs/1000} holdUs=${(target-now)/1000}")
                reportStartNs = reportNow
                reportUnique = uniqueFrames
                reportCallbacks = cb
                updateSumNs = 0L; updateMaxNs = 0L; swapSumNs = 0L; swapMaxNs = 0L
                sampleFrames = 0L; textureStepMaxNs = 0L
            }
        }
        if (++_displayFrames % 120L == 0L) {
            Log.i(TAG, "MVP_SYNC display submitted=$_displayFrames holdUs=${(target - now) / 1000L} eglTime=${egl?.supportsPresentationTime}")
        }
    }

    private fun _initGl() {
        program = _buildProgram()
        if (BuildConfig.H10_GL60_OPT) {
            stageProgram = _buildProgram(FRAG.replace("#extension GL_OES_EGL_image_external : require\n", "")
                .replace("samplerExternalOES", "sampler2D"))
            stagePos = GLES20.glGetAttribLocation(stageProgram, "aPos")
            stageTex = GLES20.glGetAttribLocation(stageProgram, "aTex")
            stageMatrix = GLES20.glGetUniformLocation(stageProgram, "uTexMatrix")
            Log.i(TAG, "H10_STAGE enabled slots=${staged.capacity} submitLeadUs=${STAGE_LEAD_NS/1000}; accepted audio clock unchanged")
        }
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aTex = GLES20.glGetAttribLocation(program, "aTex")
        uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        oesTex = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        surfaceTexture = SurfaceTexture(oesTex).also {
            if (videoW > 0 && videoH > 0) it.setDefaultBufferSize(videoW, videoH)
            val listener = SurfaceTexture.OnFrameAvailableListener {
                synchronized(lock) {
                    if (BuildConfig.H10_GL60_OPT) {
                        callbackCount.incrementAndGet()
                        if (frameAvailable) coalescedCount.incrementAndGet()
                    }
                    frameAvailable = true
                    lock.notifyAll()
                }
            }
            if (BuildConfig.H10_GL60_OPT) it.setOnFrameAvailableListener(listener, frameHandler)
            else it.setOnFrameAvailableListener(listener)
        }
    }

    private fun _buildProgram(fragment: String = FRAG): Int {
        val vs = _shader(GLES20.GL_VERTEX_SHADER, VERT)
        val fs = _shader(GLES20.GL_FRAGMENT_SHADER, fragment)
        return GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vs)
            GLES20.glAttachShader(it, fs)
            GLES20.glLinkProgram(it)
            GLES20.glDeleteShader(vs)
            GLES20.glDeleteShader(fs)
        }
    }

    private fun _shader(type: Int, src: String): Int {
        return GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, src)
            GLES20.glCompileShader(it)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(it, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) Log.e(TAG, "shader compile failed: ${GLES20.glGetShaderInfoLog(it)}")
        }
    }

    private fun _releaseGl() {
        egl?.makeCurrent()
        _deleteStageImages()
        if (stageProgram != 0) GLES20.glDeleteProgram(stageProgram)
        surfaceTexture?.release()
        surfaceTexture = null
        inputSurface?.release()
        inputSurface = null
        egl?.let {
            if (window != EGL14.EGL_NO_SURFACE) it.destroySurface(window)
            it.close()
        }
        egl = null
        window = EGL14.EGL_NO_SURFACE
    }

    companion object {
        private const val TAG = "VideoPipeline"
        private const val STAGE_LEAD_NS = 6_000_000L

        private val POS = _fb(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
        private val TEX = _fb(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))

        private fun _fb(a: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder())
                .asFloatBuffer().apply { put(a); position(0) }

        private const val VERT =
            "attribute vec2 aPos;\n" +
            "attribute vec2 aTex;\n" +
            "uniform mat4 uTexMatrix;\n" +
            "varying vec2 vTex;\n" +
            "void main() {\n" +
            "  gl_Position = vec4(aPos, 0.0, 1.0);\n" +
            "  vTex = (uTexMatrix * vec4(aTex, 0.0, 1.0)).xy;\n" +
            "}\n"

        private const val FRAG =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying vec2 vTex;\n" +
            "uniform samplerExternalOES sTex;\n" +
            "void main() {\n" +
            "  gl_FragColor = texture2D(sTex, vTex);\n" +
            "}\n"
    }
}
