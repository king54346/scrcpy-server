package com.genymobile.scrcpy.util

import java.io.IOException
import java.util.Scanner

object Command {
    @Throws(IOException::class, InterruptedException::class)
    fun exec(vararg cmd: String?) {
        val process = Runtime.getRuntime().exec(cmd)
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw IOException("Command " + cmd.contentToString() + " returned with value " + exitCode)
        }
    }

    @Throws(IOException::class, InterruptedException::class)
    fun execReadLine(vararg cmd: String?): String? {
        var result: String? = null
        val process = Runtime.getRuntime().exec(cmd)
        val scanner = Scanner(process.inputStream)
        if (scanner.hasNextLine()) {
            result = scanner.nextLine()
        }
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw IOException("Command " + cmd.contentToString() + " returned with value " + exitCode)
        }
        return result
    }

    @Throws(IOException::class, InterruptedException::class)
    fun execReadOutput(vararg cmd: String?): String {
        val process = Runtime.getRuntime().exec(cmd)
        val output: String = IO.toString(process.inputStream)
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw IOException("Command " + cmd.contentToString() + " returned with value " + exitCode)
        }
        return output
    }
}