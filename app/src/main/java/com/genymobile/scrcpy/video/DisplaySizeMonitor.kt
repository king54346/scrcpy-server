package com.genymobile.scrcpy.video

import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.IDisplayWindowListener
import androidx.annotation.RequiresApi
import com.genymobile.scrcpy.AndroidVersions
import com.genymobile.scrcpy.device.Device
import com.genymobile.scrcpy.device.Size
import com.genymobile.scrcpy.util.Ln
import com.genymobile.scrcpy.wrappers.DisplayManager
import com.genymobile.scrcpy.wrappers.DisplayWindowListener
import com.genymobile.scrcpy.wrappers.ServiceManager

/**
 * 监听显示器尺寸变化
 *
 * 由于 Android 14/15 的 DisplayListener 存在 bug，
 * 在 Android 14+ 使用 DisplayWindowListener 作为替代方案
 */
class DisplaySizeMonitor {

    /**
     * 显示器尺寸变化回调
     */
    fun interface Listener {
        fun onDisplaySizeChanged()
    }

    // === 状态变量 ===
    private var displayId = Device.DISPLAY_ID_NONE
    private var listener: Listener? = null
    private var monitoringStrategy: MonitoringStrategy? = null

    @get:Synchronized
    @set:Synchronized
    var sessionDisplaySize: Size? = null

    // === 公开方法 ===

    /**
     * 启动监听
     *
     * @param displayId 要监听的显示器 ID
     * @param listener 尺寸变化回调
     */
    fun start(displayId: Int, listener: Listener) {
        require(this.listener == null) { "DisplaySizeMonitor already started" }
        require(this.displayId == Device.DISPLAY_ID_NONE) { "DisplayId already set" }

        this.displayId = displayId
        this.listener = listener

        // 根据 Android 版本选择监听策略
        monitoringStrategy = createMonitoringStrategy(displayId)
        monitoringStrategy?.start()
    }

    /**
     * 停止并释放监听器
     *
     * 可以安全地多次调用
     */
    fun stopAndRelease() {
        monitoringStrategy?.stop()
        monitoringStrategy = null
        listener = null
    }

    // === 私有方法 ===

    /**
     * 创建监听策略
     */
    private fun createMonitoringStrategy(displayId: Int): MonitoringStrategy {
        return if (shouldUseDisplayListener()) {
            DisplayListenerStrategy(displayId, ::handleDisplayChanged)
        } else {
            DisplayWindowListenerStrategy(displayId, ::handleDisplayChanged)
        }
    }

    /**
     * 判断是否使用 DisplayListener
     *
     * Android 14+ 的 DisplayListener 可能不工作，使用 DisplayWindowListener 替代
     */
    private fun shouldUseDisplayListener(): Boolean {
        return Build.VERSION.SDK_INT < AndroidVersions.API_34_ANDROID_14
    }

    /**
     * 处理显示器变化事件
     */
    private fun handleDisplayChanged() {
        val displayInfo = ServiceManager.displayManager?.getDisplayInfo(displayId)

        if (displayInfo == null) {
            handleDisplayInfoUnavailable()
        } else {
            handleDisplayInfoAvailable(displayInfo.size)
        }
    }

    /**
     * 处理显示器信息不可用的情况
     */
    private fun handleDisplayInfoUnavailable() {
        Ln.w("DisplayInfo for $displayId cannot be retrieved")
        logVerbose("requestReset(): $sessionDisplaySize -> (unknown)")

        sessionDisplaySize = null
        listener?.onDisplaySizeChanged()
    }

    /**
     * 处理显示器信息可用的情况
     */
    private fun handleDisplayInfoAvailable(newSize: Size) {
        val currentSize = sessionDisplaySize

        if (newSize != currentSize) {
            logVerbose("requestReset(): $currentSize -> $newSize")

            // 立即更新尺寸，避免重复的 reset 请求
            sessionDisplaySize = newSize
            listener?.onDisplaySizeChanged()
        } else {
            logVerbose("Size not changed ($newSize): do not requestReset()")
        }
    }

    /**
     * 输出详细日志
     */
    private fun logVerbose(message: String) {
        if (Ln.isEnabled(Ln.Level.VERBOSE)) {
            Ln.v("DisplaySizeMonitor: $message")
        }
    }

    // === 监听策略接口 ===

    /**
     * 监听策略基类
     */
    private interface MonitoringStrategy {
        fun start()
        fun stop()
    }

    // === DisplayListener 策略 (Android < 14) ===

    /**
     * 使用 DisplayListener 监听 (Android < 14)
     */
    private inner class DisplayListenerStrategy(
        private val displayId: Int,
        private val onChanged: () -> Unit
    ) : MonitoringStrategy {

        private var handlerThread: HandlerThread? = null
        private var listenerHandle: DisplayManager.DisplayListenerHandle? = null

        override fun start() {
            // 创建后台线程
            handlerThread = HandlerThread("DisplayListener").apply { start() }
            val handler = Handler(handlerThread!!.looper)

            // 注册监听器
            listenerHandle = ServiceManager.displayManager?.registerDisplayListener(
                { eventDisplayId ->
                    if (eventDisplayId == displayId) {
                        logVerbose("onDisplayChanged($eventDisplayId)")
                        onChanged()
                    }
                },
                handler
            )
        }

        override fun stop() {
            listenerHandle?.let {
                ServiceManager.displayManager?.unregisterDisplayListener(it)
                listenerHandle = null
            }
            handlerThread?.quitSafely()
            handlerThread = null
        }
    }

    // === DisplayWindowListener 策略 (Android >= 14) ===

    /**
     * 使用 DisplayWindowListener 监听 (Android >= 14)
     */
    private inner class DisplayWindowListenerStrategy(
        private val displayId: Int,
        private val onChanged: () -> Unit
    ) : MonitoringStrategy {

        private var windowListener: IDisplayWindowListener? = null

        @RequiresApi(Build.VERSION_CODES.R)
        override fun start() {
            windowListener = object : DisplayWindowListener() {
                override fun onDisplayConfigurationChanged(
                    eventDisplayId: Int,
                    newConfig: Configuration
                ) {
                    if (eventDisplayId == displayId) {
                        logVerbose("onDisplayConfigurationChanged($eventDisplayId)")
                        onChanged()
                    }
                }
            }
            ServiceManager.windowManager?.registerDisplayWindowListener(windowListener)
        }

        @RequiresApi(Build.VERSION_CODES.R)
        override fun stop() {
            windowListener?.let {
                ServiceManager.windowManager?.unregisterDisplayWindowListener(it)
                windowListener = null
            }
        }
    }
}