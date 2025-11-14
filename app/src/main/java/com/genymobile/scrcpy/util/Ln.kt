package com.genymobile.scrcpy.util

import android.util.Log
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.PrintStream

/**
 * Log both to Android logger (so that logs are visible in "adb logcat") and standard output/error (so that they are visible in the terminal
 * directly).
 */
object Ln {
    private const val TAG = "scrcpy"
    private const val PREFIX = "[server] "

    private val CONSOLE_OUT = PrintStream(FileOutputStream(FileDescriptor.out))
    private val CONSOLE_ERR = PrintStream(FileOutputStream(FileDescriptor.err))

    private var threshold = Level.INFO

    fun disableSystemStreams() {
        val nullStream = PrintStream(NullOutputStream())
        System.setOut(nullStream)
        System.setErr(nullStream)
    }

    /**
     * Initialize the log level.
     *
     *
     * Must be called before starting any new thread.
     *
     * @param level the log level
     */
    fun initLogLevel(level: Level) {
        threshold = level
    }

    fun isEnabled(level: Level): Boolean {
        return level.ordinal >= threshold.ordinal
    }

    fun v(message: String) {
        if (isEnabled(Level.VERBOSE)) {
            Log.v(TAG, message)
            CONSOLE_OUT.print(PREFIX + "VERBOSE: " + message + '\n')
        }
    }

    fun d(message: String) {
        if (isEnabled(Level.DEBUG)) {
            Log.d(TAG, message)
            CONSOLE_OUT.print(PREFIX + "DEBUG: " + message + '\n')
        }
    }

    fun i(message: String) {
        if (isEnabled(Level.INFO)) {
            Log.i(TAG, message)
            CONSOLE_OUT.print(PREFIX + "INFO: " + message + '\n')
        }
    }

    @JvmOverloads
    fun w(message: String, throwable: Throwable? = null) {
        if (isEnabled(Level.WARN)) {
            Log.w(TAG, message, throwable)
            CONSOLE_ERR.print(PREFIX + "WARN: " + message + '\n')
            throwable?.printStackTrace(CONSOLE_ERR)
        }
    }

    @JvmOverloads
    fun e(message: String, throwable: Throwable? = null) {
        if (isEnabled(Level.ERROR)) {
            Log.e(TAG, message, throwable)
            CONSOLE_ERR.print(PREFIX + "ERROR: " + message + '\n')
            throwable?.printStackTrace(CONSOLE_ERR)
        }
    }

    enum class Level {
        VERBOSE, DEBUG, INFO, WARN, ERROR
    }

    internal class NullOutputStream : OutputStream() {
        override fun write(b: ByteArray) {
            // ignore
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            // ignore
        }

        override fun write(b: Int) {
            // ignore
        }
    }
}