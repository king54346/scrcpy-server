package com.genymobile.scrcpy.video

import android.media.MediaCodec
import com.genymobile.scrcpy.video.SurfaceCapture.CaptureListener
import java.util.concurrent.atomic.AtomicBoolean

class CaptureReset : CaptureListener {
    private val reset = AtomicBoolean()

    // Current instance of MediaCodec to "interrupt" on reset
    private var runningMediaCodec: MediaCodec? = null

    fun consumeReset(): Boolean {
        return reset.getAndSet(false)
    }

    @Synchronized
    fun reset() {
        reset.set(true)
        if (runningMediaCodec != null) {
            try {
                runningMediaCodec!!.signalEndOfInputStream()
            } catch (e: IllegalStateException) {
                // ignore
            }
        }
    }

    @Synchronized
    fun setRunningMediaCodec(runningMediaCodec: MediaCodec?) {
        this.runningMediaCodec = runningMediaCodec
    }

    override fun onInvalidated() {
        reset()
    }
}