package com.genymobile.scrcpy.util

import android.util.Log
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.PrintStream

/**
 * 双输出日志工具
 *
 * 同时输出到:
 * 1. Android logcat (通过 adb logcat 查看)
 * 2. 标准输出/错误流 (终端直接可见)
 */
object Ln {
    private const val TAG = "scrcpy"
    private const val PREFIX = "[server] "

    // 直接写文件描述符，绕过 System.out/err 重定向
    private val CONSOLE_OUT = PrintStream(FileOutputStream(FileDescriptor.out))
    private val CONSOLE_ERR = PrintStream(FileOutputStream(FileDescriptor.err))

    @Volatile
    private var threshold = Level.INFO

    /**
     * 禁用系统标准流
     *
     * 防止其他代码的 println() 干扰日志输出
     */
    fun disableSystemStreams() {
        val nullStream = PrintStream(NullOutputStream())
        System.setOut(nullStream)
        System.setErr(nullStream)
    }

    /**
     * 设置日志级别阈值
     *
     * 必须在启动其他线程前调用
     */
    fun initLogLevel(level: Level) {
        threshold = level
    }

    /**
     * 检查指定级别是否启用
     */
    fun isEnabled(level: Level): Boolean = level >= threshold

    // ============================================
    // 日志输出方法
    // ============================================

    fun v(message: String) {
        if (isEnabled(Level.VERBOSE)) {
            log(Log.VERBOSE, "VERBOSE", message, CONSOLE_OUT)
        }
    }

    fun d(message: String) {
        if (isEnabled(Level.DEBUG)) {
            log(Log.DEBUG, "DEBUG", message, CONSOLE_OUT)
        }
    }

    fun i(message: String) {
        if (isEnabled(Level.INFO)) {
            log(Log.INFO, "INFO", message, CONSOLE_OUT)
        }
    }

    fun w(message: String, throwable: Throwable? = null) {
        if (isEnabled(Level.WARN)) {
            log(Log.WARN, "WARN", message, CONSOLE_ERR, throwable)
        }
    }

    fun e(message: String, throwable: Throwable? = null) {
        if (isEnabled(Level.ERROR)) {
            log(Log.ERROR, "ERROR", message, CONSOLE_ERR, throwable)
        }
    }

    // ============================================
    // 内部实现
    // ============================================

    /**
     * 统一的日志输出实现
     */
    private fun log(
        priority: Int,
        levelName: String,
        message: String,
        console: PrintStream,
        throwable: Throwable? = null
    ) {
        // 输出到 logcat
        when (priority) {
            Log.VERBOSE -> Log.v(TAG, message, throwable)
            Log.DEBUG -> Log.d(TAG, message, throwable)
            Log.INFO -> Log.i(TAG, message, throwable)
            Log.WARN -> Log.w(TAG, message, throwable)
            Log.ERROR -> Log.e(TAG, message, throwable)
        }

        // 输出到控制台
        console.println("$PREFIX$levelName: $message")
        throwable?.printStackTrace(console)
    }

    /**
     * 日志级别
     *
     * Enum 自带 compareTo，按声明顺序比较
     */
    enum class Level {
        VERBOSE,
        DEBUG,
        INFO,
        WARN,
        ERROR
    }

    /**
     * 空输出流 (用于禁用系统流)
     */
    private class NullOutputStream : OutputStream() {
        override fun write(b: Int) {}
        override fun write(b: ByteArray) {}
        override fun write(b: ByteArray, off: Int, len: Int) {}
    }
}