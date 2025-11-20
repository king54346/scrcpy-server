package com.genymobile.scrcpy

import android.os.BatteryManager
import android.os.Build
import android.os.Looper
import android.system.ErrnoException
import android.system.Os
import androidx.annotation.RequiresApi
import com.genymobile.scrcpy.device.Device
import com.genymobile.scrcpy.util.Ln
import com.genymobile.scrcpy.util.Settings
import com.genymobile.scrcpy.util.SettingsException
import com.genymobile.scrcpy.wrappers.ServiceManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.IOException
import java.io.OutputStream

/**
 * 清理守护进程
 *
 * 负责在主进程被杀死后恢复设备设置。
 * 通过创建独立进程监听主进程状态，确保即使在 USB 断开等极端情况下也能正确清理。
 *主进程 (scrcpy server)           子进程 (cleanup monitor)
 *     |                                     |
 *     | 启动时修改设置                        |
 *     | 创建子进程 ----------------->        |
 *     | 保持管道连接                          | 监听管道
 *     |                                    |
 *     | 被杀死 ✗                            |
 *     | 管道关闭 ------------------>        | 检测到管道关闭
 *                                         | 恢复所有设置 ✓
 * 工作原理:
 * 1. 主进程启动时修改设备设置(如显示触摸点、保持唤醒等)并记录原值
 * 2. 创建独立的子进程监听主进程的 stdout 管道
 * 3. 主进程死亡时管道关闭，子进程检测到后恢复所有设置
*/

@RequiresApi(Build.VERSION_CODES.Q)
class CleanUp private constructor(options: Options) {
    // 协程作用域
    private val cleanupScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 待恢复的显示电源状态 (使用 StateFlow 替代 AtomicBoolean + wait/notify)
    private val restoreDisplayPowerFlow = MutableStateFlow<Boolean?>(null)

    private var cleanupJob: Job? = null

    init {
        cleanupJob = cleanupScope.launch {
            runCleanUp(options)
        }
    }

    /**
     * 取消清理协程
     */
    fun interrupt() {
        cleanupJob?.cancel()
    }

    suspend fun join() {
        cleanupJob?.join()
    }

    /**
     * 启动时的准备工作
     */
    private suspend fun runCleanUp(options: Options) {
        val restoreState = RestoreState(
            disableShowTouches = prepareShowTouches(options),
            restoreStayOn = prepareStayAwake(options),
            restoreScreenOffTimeout = prepareScreenTimeout(options),
            restoreDisplayImePolicy = prepareDisplayImePolicy(options)
        )

        try {
            runMonitorProcess(options, restoreState)
        } catch (e: IOException) {
            Ln.e("Clean up I/O exception", e)
        } catch (e: CancellationException) {
            Ln.d("Clean up cancelled")
        }
    }

    private fun prepareShowTouches(options: Options): Boolean {
        if (!options.showTouches) return false

        return try {
            val oldValue = Settings.getAndPutValue(Settings.TABLE_SYSTEM, "show_touches", "1")
            oldValue != "1"
        } catch (e: SettingsException) {
            Ln.e("Could not change \"show_touches\"", e)
            false
        }
    }

    private fun prepareStayAwake(options: Options): Int {
        if (!options.stayAwake) return -1

        val targetValue = BatteryManager.BATTERY_PLUGGED_AC or
                BatteryManager.BATTERY_PLUGGED_USB or
                BatteryManager.BATTERY_PLUGGED_WIRELESS

        return try {
            val oldValue = Settings.getAndPutValue(
                Settings.TABLE_GLOBAL,
                "stay_on_while_plugged_in",
                targetValue.toString()
            )

            oldValue?.toIntOrNull()?.let { currentValue ->
                if (currentValue != targetValue) currentValue else -1
            } ?: -1
        } catch (e: SettingsException) {
            Ln.e("Could not change \"stay_on_while_plugged_in\"", e)
            -1
        }
    }

    private fun prepareScreenTimeout(options: Options): Int {
        val screenOffTimeout = options.screenOffTimeout
        if (screenOffTimeout == -1) return -1

        return try {
            val oldValue = Settings.getAndPutValue(
                Settings.TABLE_SYSTEM,
                "screen_off_timeout",
                screenOffTimeout.toString()
            )

            oldValue?.toIntOrNull()?.let { currentValue ->
                if (currentValue != screenOffTimeout) currentValue else -1
            } ?: -1
        } catch (e: SettingsException) {
            Ln.e("Could not change \"screen_off_timeout\"", e)
            -1
        }
    }

    private fun prepareDisplayImePolicy(options: Options): Int {
        val displayId = options.displayId
        if (displayId <= 0) return -1

        val displayImePolicy = options.displayImePolicy
        if (displayImePolicy == -1) return -1

        val currentPolicy = ServiceManager.windowManager?.getDisplayImePolicy(displayId)

        return if (currentPolicy != displayImePolicy) {
            ServiceManager.windowManager?.setDisplayImePolicy(displayId, displayImePolicy)
            currentPolicy ?: -1
        } else {
            -1
        }
    }

    /**
     * 启动独立的监听进程
     */
    private suspend fun runMonitorProcess(options: Options, restoreState: RestoreState) {
        val cmd = buildCommand(options, restoreState)
        val process = withContext(Dispatchers.IO) {
            startCleanupProcess(cmd)
        }
        val outputStream = process.outputStream

        monitorLoop(outputStream)
    }

    private fun buildCommand(options: Options, restoreState: RestoreState): Array<String> {
        return arrayOf(
            "app_process",
            "/",
            CleanUp::class.java.name,
            options.displayId.toString(),
            restoreState.restoreStayOn.toString(),
            restoreState.disableShowTouches.toString(),
            options.powerOffScreenOnClose.toString(),
            restoreState.restoreScreenOffTimeout.toString(),
            restoreState.restoreDisplayImePolicy.toString(),
        )
    }

    @Throws(IOException::class)
    private fun startCleanupProcess(cmd: Array<String>): Process {
        val builder = ProcessBuilder(*cmd)
        builder.environment()["CLASSPATH"] = Server.SERVER_PATH
        return builder.start()
    }

    /**
     * 主监听循环 (协程版本)
     * 使用 Flow 监听变更，无需手动 wait/notify
     */
    private suspend fun monitorLoop(outputStream: OutputStream) {
        try {
            // 监听 restoreDisplayPower 的变更
            restoreDisplayPowerFlow
                .filterNotNull()
                .collect { restoreDisplayPower ->
                    withContext(Dispatchers.IO) {
                        outputStream.write(if (restoreDisplayPower) 1 else 0)
                        outputStream.flush()
                    }
                    // 重置为 null，等待下一次变更
                    restoreDisplayPowerFlow.value = null
                }
        } catch (e: IOException) {
            Ln.e("Monitor loop I/O error", e)
        }
    }

    /**
     * 动态设置是否恢复显示电源
     */
    fun setRestoreDisplayPower(restoreDisplayPower: Boolean) {
        restoreDisplayPowerFlow.value = restoreDisplayPower
    }

    private data class RestoreState(
        val disableShowTouches: Boolean,
        val restoreStayOn: Int,
        val restoreScreenOffTimeout: Int,
        val restoreDisplayImePolicy: Int
    )

    companion object {
        fun start(options: Options): CleanUp {
            return CleanUp(options)
        }

        fun unlinkSelf() {
            try {
                File(Server.SERVER_PATH).delete()
            } catch (e: Exception) {
                Ln.e("Could not unlink server", e)
            }
        }

        @Suppress("deprecation")
        private fun prepareMainLooper() {
            Looper.prepareMainLooper()
        }

        /**
         * 子进程入口点 (保持阻塞式，因为这是独立进程)
         */
        @RequiresApi(Build.VERSION_CODES.Q)
        @JvmStatic
        fun main(args: Array<String>) {
            try {
                Os.setsid()
            } catch (e: ErrnoException) {
                Ln.e("setsid() failed", e)
            }

            unlinkSelf()
            prepareMainLooper()

            val cleanupParams = parseCleanupParams(args)

            // 子进程使用协程运行
            runBlocking {
                val dynamicState = waitForParentDeath()
                executeCleanup(cleanupParams, dynamicState)
            }

            System.exit(0)
        }

        private fun parseCleanupParams(args: Array<String>): CleanupParams {
            return CleanupParams(
                displayId = args[0].toInt(),
                restoreStayOn = args[1].toInt(),
                disableShowTouches = args[2].toBoolean(),
                powerOffScreen = args[3].toBoolean(),
                restoreScreenOffTimeout = args[4].toInt(),
                restoreDisplayImePolicy = args[5].toInt()
            )
        }

        /**
         * 等待父进程死亡 (协程版本)
         */
        private suspend fun waitForParentDeath(): DynamicState = withContext(Dispatchers.IO) {
            var restoreDisplayPower = false

            try {
                var msg: Int
                while (System.`in`.read().also { msg = it } != -1) {
                    check(msg == 0 || msg == 1) { "Invalid message: $msg" }
                    restoreDisplayPower = (msg == 1)
                }
            } catch (e: IOException) {
                // 父进程死亡时会触发此异常
            }

            DynamicState(restoreDisplayPower)
        }

        private fun executeCleanup(params: CleanupParams, state: DynamicState) {
            Ln.i("Cleaning up")

            restoreShowTouches(params.disableShowTouches)
            restoreStayAwake(params.restoreStayOn)
            restoreScreenTimeout(params.restoreScreenOffTimeout)
            restoreDisplayImePolicy(params.displayId, params.restoreDisplayImePolicy)
            handleDisplayPower(params, state)
        }

        private fun restoreShowTouches(disable: Boolean) {
            if (!disable) return

            Ln.i("Disabling \"show touches\"")
            try {
                Settings.putValue(Settings.TABLE_SYSTEM, "show_touches", "0")
            } catch (e: SettingsException) {
                Ln.e("Could not restore \"show_touches\"", e)
            }
        }

        private fun restoreStayAwake(value: Int) {
            if (value == -1) return

            Ln.i("Restoring \"stay awake\"")
            try {
                Settings.putValue(Settings.TABLE_GLOBAL, "stay_on_while_plugged_in", value.toString())
            } catch (e: SettingsException) {
                Ln.e("Could not restore \"stay_on_while_plugged_in\"", e)
            }
        }

        private fun restoreScreenTimeout(value: Int) {
            if (value == -1) return

            Ln.i("Restoring \"screen off timeout\"")
            try {
                Settings.putValue(Settings.TABLE_SYSTEM, "screen_off_timeout", value.toString())
            } catch (e: SettingsException) {
                Ln.e("Could not restore \"screen_off_timeout\"", e)
            }
        }

        private fun restoreDisplayImePolicy(displayId: Int, value: Int) {
            if (value == -1) return

            Ln.i("Restoring \"display IME policy\"")
            ServiceManager.windowManager?.setDisplayImePolicy(displayId, value)
        }

        private fun handleDisplayPower(params: CleanupParams, state: DynamicState) {
            val targetDisplayId = if (params.displayId != Device.DISPLAY_ID_NONE) {
                params.displayId
            } else {
                0
            }

            if (!Device.isScreenOn(targetDisplayId)) return

            when {
                params.powerOffScreen -> {
                    Ln.i("Power off screen")
                    Device.powerOffScreen(targetDisplayId)
                }
                state.restoreDisplayPower -> {
                    Ln.i("Restoring display power")
                    Device.setDisplayPower(targetDisplayId, true)
                }
            }
        }

        private data class CleanupParams(
            val displayId: Int,
            val restoreStayOn: Int,
            val disableShowTouches: Boolean,
            val powerOffScreen: Boolean,
            val restoreScreenOffTimeout: Int,
            val restoreDisplayImePolicy: Int
        )

        private data class DynamicState(
            val restoreDisplayPower: Boolean
        )
    }
}