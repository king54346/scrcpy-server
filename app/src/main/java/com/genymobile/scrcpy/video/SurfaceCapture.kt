package com.genymobile.scrcpy.video

import android.view.Surface
import com.genymobile.scrcpy.device.ConfigurationException
import com.genymobile.scrcpy.device.Size
import java.io.IOException

/**
 * A video source which can be rendered on a Surface for encoding.
 */
abstract class SurfaceCapture {
    interface CaptureListener {
        fun onInvalidated()
    }

    private var listener: CaptureListener? = null

    /**
     * Notify the listener that the capture has been invalidated (for example, because its size changed).
     */
    protected fun invalidate() {
        listener!!.onInvalidated()
    }

    /**
     * Called once before the first capture starts.
     */
    @Throws(ConfigurationException::class, IOException::class)
    fun init(listener: CaptureListener?) {
        this.listener = listener
        init()
    }

    /**
     * Called once before the first capture starts.
     */
    @Throws(ConfigurationException::class, IOException::class)
    protected abstract fun init()

    /**
     * Called after the last capture ends (if and only if [.init] has been called).
     */
    abstract fun release()

    /**
     * Called once before each capture starts, before [.getSize].
     */
    @Throws(ConfigurationException::class, IOException::class)
    open fun prepare() {
        // empty by default
    }

    /**
     * Start the capture to the target surface.
     *
     * @param surface the surface which will be encoded
     */
    @Throws(IOException::class)
    abstract fun start(surface: Surface?)

    /**
     * Stop the capture.
     */
    open fun stop() {
        // Do nothing by default
    }

    /**
     * Return the video size
     *
     * @return the video size
     */
    abstract val size: Size?

    /**
     * Set the maximum capture size (set by the encoder if it does not support the current size).
     *
     * @param maxSize Maximum size
     */
    abstract fun setMaxSize(maxSize: Int): Boolean

    open val isClosed: Boolean
        /**
         * Indicate if the capture has been closed internally.
         *
         * @return `true` is the capture is closed, `false` otherwise.
         */
        get() = false

    /**
     * Manually request to invalidate (typically a user request).
     *
     *
     * The capture implementation is free to ignore the request and do nothing.
     */
    abstract fun requestInvalidate()
}