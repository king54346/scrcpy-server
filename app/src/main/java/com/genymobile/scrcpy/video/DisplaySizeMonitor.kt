package com.genymobile.scrcpy.video

import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.IDisplayWindowListener
import com.genymobile.scrcpy.AndroidVersions
import com.genymobile.scrcpy.device.Device
import com.genymobile.scrcpy.device.Size
import com.genymobile.scrcpy.util.Ln
import com.genymobile.scrcpy.wrappers.DisplayManager
import com.genymobile.scrcpy.wrappers.DisplayWindowListener
import com.genymobile.scrcpy.wrappers.ServiceManager

class DisplaySizeMonitor {

    fun interface Listener {
        fun onDisplaySizeChanged()
    }

    companion object {
        // On Android 14, DisplayListener may be broken (it never sends events). This is fixed in recent Android 14 upgrades, but we can't really
        // detect it directly, so register a DisplayWindowListener (introduced in Android 11) to listen to configuration changes instead.
        // It has been broken again after an Android 15 upgrade: <https://github.com/Genymobile/scrcpy/issues/5908>
        // So use the default method only before Android 14.
        private val USE_DEFAULT_METHOD = Build.VERSION.SDK_INT < AndroidVersions.API_34_ANDROID_14
    }

    private var displayListenerHandle: DisplayManager.DisplayListenerHandle? = null
    private var handlerThread: HandlerThread? = null

    private var displayWindowListener: IDisplayWindowListener? = null

    private var displayId = Device.DISPLAY_ID_NONE

    @get:Synchronized
    @set:Synchronized
    var sessionDisplaySize: Size? = null

    private var listener: Listener? = null

    fun start(displayId: Int, listener: Listener) {
        // Once started, the listener and the displayId must never change
        require(this.listener == null) { "DisplaySizeMonitor already started" }
        this.listener = listener

        check(this.displayId == Device.DISPLAY_ID_NONE) { "DisplayId already set" }
        this.displayId = displayId

        if (USE_DEFAULT_METHOD) {
            handlerThread = HandlerThread("DisplayListener").apply {
                start()
            }
            val handler = Handler(handlerThread!!.looper)

            displayListenerHandle =
                ServiceManager.displayManager?.registerDisplayListener({ eventDisplayId ->
                    if (Ln.isEnabled(Ln.Level.VERBOSE)) {
                        Ln.v("DisplaySizeMonitor: onDisplayChanged($eventDisplayId)")
                    }

                    if (eventDisplayId == displayId) {
                        checkDisplaySizeChanged()
                    }
                }, handler)
        } else {
            displayWindowListener = object : DisplayWindowListener() {
                override fun onDisplayConfigurationChanged(eventDisplayId: Int, newConfig: Configuration) {
                    if (Ln.isEnabled(Ln.Level.VERBOSE)) {
                        Ln.v("DisplaySizeMonitor: onDisplayConfigurationChanged($eventDisplayId)")
                    }

                    if (eventDisplayId == displayId) {
                        checkDisplaySizeChanged()
                    }
                }
            }
            ServiceManager.windowManager?.registerDisplayWindowListener(displayWindowListener)
        }
    }

    /**
     * Stop and release the monitor.
     *
     * It must not be used anymore.
     * It is ok to call this method even if [start] was not called.
     */
    fun stopAndRelease() {
        if (USE_DEFAULT_METHOD) {
            // displayListenerHandle may be null if registration failed
            displayListenerHandle?.let {
                ServiceManager.displayManager?.unregisterDisplayListener(it)
                displayListenerHandle = null
            }

            handlerThread?.quitSafely()
        } else {
            displayWindowListener?.let {
                ServiceManager.windowManager?.unregisterDisplayWindowListener(it)
            }
        }
    }

    private fun checkDisplaySizeChanged() {
        val di = ServiceManager.displayManager?.getDisplayInfo(displayId)

        if (di == null) {
            Ln.w("DisplayInfo for $displayId cannot be retrieved")
            // We can't compare with the current size, so reset unconditionally
            if (Ln.isEnabled(Ln.Level.VERBOSE)) {
                Ln.v("DisplaySizeMonitor: requestReset(): $sessionDisplaySize -> (unknown)")
            }
            sessionDisplaySize = null
            listener?.onDisplaySizeChanged()
        } else {
            val size = di.size
            val currentSessionDisplaySize = sessionDisplaySize

            // .equals() also works if sessionDisplaySize == null
            if (size != currentSessionDisplaySize) {
                // Reset only if the size is different
                if (Ln.isEnabled(Ln.Level.VERBOSE)) {
                    Ln.v("DisplaySizeMonitor: requestReset(): $currentSessionDisplaySize -> $size")
                }
                // Set the new size immediately, so that a future onDisplayChanged() event called before the asynchronous prepare()
                // considers that the current size is the requested size (to avoid a duplicate requestReset())
                sessionDisplaySize = size
                listener?.onDisplaySizeChanged()
            } else if (Ln.isEnabled(Ln.Level.VERBOSE)) {
                Ln.v("DisplaySizeMonitor: Size not changed ($size): do not requestReset()")
            }
        }
    }
}