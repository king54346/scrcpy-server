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
import java.io.File
import java.io.IOException
import java.io.OutputStream

/**
 * 清理守护进程
 *
 * 负责在主进程被杀死后恢复设备设置。
 * 通过创建独立进程监听主进程状态，确保即使在 USB 断开等极端情况下也能正确清理。
 *
 * 工作原理:
 * 1. 主进程启动时修改设备设置(如显示触摸点、保持唤醒等)并记录原值
 * 2. 创建独立的子进程监听主进程的 stdout 管道
 * 3. 主进程死亡时管道关闭，子进程检测到后恢复所有设置
 */
@RequiresApi(Build.VERSION_CODES.Q)
class CleanUp private constructor(options: Options) {
    // 待处理的动态变更标志位
    private var pendingChanges = 0
    private var pendingRestoreDisplayPower = false

    private val thread: Thread
    private var interrupted = false

    init {
        thread = Thread({ runCleanUp(options) }, "cleanup")
        thread.start()
    }

    /**
     * 中断清理线程
     * 注意: 不使用 thread.interrupt() 以避免中断 Command.exec()
     */
    @Synchronized
    fun interrupt() {
        interrupted = true
        (this as Object).notify()
    }

    @Throws(InterruptedException::class)
    fun join() {
        thread.join()
    }

    /**
     * 启动时的准备工作:
     * 1. 修改所有需要的设置
     * 2. 记录原始值用于恢复
     * 3. 启动监听进程
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun runCleanUp(options: Options) {
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
        }
    }

    /**
     * 准备 "显示触摸点" 设置
     * @return 是否需要在清理时禁用
     */
    private fun prepareShowTouches(options: Options): Boolean {
        if (!options.showTouches) return false

        return try {
            val oldValue = Settings.getAndPutValue(Settings.TABLE_SYSTEM, "show_touches", "1")
            // 只有原来是关闭的，才需要恢复
            oldValue != "1"
        } catch (e: SettingsException) {
            Ln.e("Could not change \"show_touches\"", e)
            false
        }
    }

    /**
     * 准备 "保持唤醒" 设置
     * @return 需要恢复的原值，-1 表示不需要恢复
     */
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

    /**
     * 准备 "屏幕超时" 设置
     * @return 需要恢复的原值，-1 表示不需要恢复
     */
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

    /**
     * 准备 "显示 IME 策略" 设置
     * @return 需要恢复的原值，-1 表示不需要恢复
     */
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
     * 该进程会在主进程死亡后执行清理工作
     */
    @Throws(IOException::class)
    private fun runMonitorProcess(options: Options, restoreState: RestoreState) {
        val cmd = buildCommand(options, restoreState)
        val process = startCleanupProcess(cmd)
        val outputStream = process.outputStream

        monitorLoop(outputStream)
    }

    /**
     * 构建子进程启动命令
     */
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

    /**
     * 启动清理进程
     */
    @Throws(IOException::class)
    private fun startCleanupProcess(cmd: Array<String>): Process {
        val builder = ProcessBuilder(*cmd)
        builder.environment()["CLASSPATH"] = Server.SERVER_PATH
        return builder.start()
    }

    /**
     * 主监听循环
     * 等待动态变更通知并发送给子进程
     */
    @Throws(IOException::class)
    private fun monitorLoop(outputStream: OutputStream) {
        while (true) {
            val changes = waitForChanges() ?: break

            if (changes.hasDisplayPowerChange) {
                outputStream.write(if (changes.restoreDisplayPower) 1 else 0)
                outputStream.flush()
            }
        }
    }

    /**
     * 等待待处理的变更
     * @return 变更信息，如果被中断则返回 null
     */
    @Synchronized
    private fun waitForChanges(): PendingChanges? {
        while (!interrupted && pendingChanges == 0) {
            try {
                (this as Object).wait()
            } catch (e: InterruptedException) {
                throw AssertionError("Clean up thread MUST NOT be interrupted")
            }
        }

        if (interrupted) return null

        val changes = PendingChanges(
            hasDisplayPowerChange = (pendingChanges and PENDING_CHANGE_DISPLAY_POWER) != 0,
            restoreDisplayPower = pendingRestoreDisplayPower
        )

        pendingChanges = 0
        return changes
    }

    /**
     * 动态设置是否恢复显示电源
     */
    @Synchronized
    fun setRestoreDisplayPower(restoreDisplayPower: Boolean) {
        pendingRestoreDisplayPower = restoreDisplayPower
        pendingChanges = pendingChanges or PENDING_CHANGE_DISPLAY_POWER
        (this as Object).notify()
    }

    /**
     * 需要恢复的设置状态
     */
    private data class RestoreState(
        val disableShowTouches: Boolean,
        val restoreStayOn: Int,
        val restoreScreenOffTimeout: Int,
        val restoreDisplayImePolicy: Int
    )

    /**
     * 待处理的变更
     */
    private data class PendingChanges(
        val hasDisplayPowerChange: Boolean,
        val restoreDisplayPower: Boolean
    )

    companion object {
        // 动态选项标志位
        private const val PENDING_CHANGE_DISPLAY_POWER = 1 shl 0

        fun start(options: Options): CleanUp {
            return CleanUp(options)
        }

        /**
         * 删除服务端 jar 文件
         */
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
         * 子进程入口点
         * 阻塞等待父进程死亡，然后执行清理工作
         */
        @RequiresApi(Build.VERSION_CODES.Q)
        @JvmStatic
        fun main(args: Array<String>) {
            try {
                // 创建新会话以避免与服务进程一起被终止
                Os.setsid()
            } catch (e: ErrnoException) {
                Ln.e("setsid() failed", e)
            }

            unlinkSelf()
            prepareMainLooper()

            val cleanupParams = parseCleanupParams(args)
            val dynamicState = waitForParentDeath()

            executeCleanup(cleanupParams, dynamicState)
            System.exit(0)
        }

        /**
         * 解析清理参数
         */
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
         * 等待父进程死亡
         * 通过监听 stdin 实现 - 当父进程死亡时管道关闭
         */
        private fun waitForParentDeath(): DynamicState {
            var restoreDisplayPower = false

            try {
                var msg: Int
                while (System.`in`.read().also { msg = it } != -1) {
                    // 动态更新: 是否恢复显示电源
                    check(msg == 0 || msg == 1) { "Invalid message: $msg" }
                    restoreDisplayPower = (msg == 1)
                }
            } catch (e: IOException) {
                // 父进程死亡时会触发此异常
            }

            return DynamicState(restoreDisplayPower)
        }

        /**
         * 执行清理操作
         */
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

        /**
         * 清理参数 (静态配置)
         */
        private data class CleanupParams(
            val displayId: Int,
            val restoreStayOn: Int,
            val disableShowTouches: Boolean,
            val powerOffScreen: Boolean,
            val restoreScreenOffTimeout: Int,
            val restoreDisplayImePolicy: Int
        )

        /**
         * 动态状态 (运行时更新)
         */
        private data class DynamicState(
            val restoreDisplayPower: Boolean
        )
    }
}