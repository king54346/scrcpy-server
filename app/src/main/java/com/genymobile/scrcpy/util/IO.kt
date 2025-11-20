package com.genymobile.scrcpy.util

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.FileDescriptor
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.Scanner

object IO {
    @Throws(IOException::class)
    private fun write(fd: FileDescriptor, from: ByteBuffer): Int {
        while (true) {
            try {
                return Os.write(fd, from)
            } catch (e: ErrnoException) {
                if (e.errno != OsConstants.EINTR) {
                    throw IOException(e)
                }
            }
        }
    }

    @Throws(IOException::class)
    fun writeFully(fd: FileDescriptor, from: ByteBuffer) {
        while (from.hasRemaining()) {
            write(fd, from)
        }
    }

    @Throws(IOException::class)
    fun writeFully(fd: FileDescriptor, buffer: ByteArray, offset: Int, len: Int) {
        writeFully(fd, ByteBuffer.wrap(buffer, offset, len))
    }

    fun toString(inputStream: InputStream): String {
        val builder = StringBuilder()
        val scanner = Scanner(inputStream)
        while (scanner.hasNextLine()) {
            builder.append(scanner.nextLine()).append('\n')
        }
        return builder.toString()
    }

    fun isBrokenPipe(e: IOException): Boolean {
        val cause = e.cause
        return cause is ErrnoException && cause.errno == OsConstants.EPIPE
    }

    fun isBrokenPipe(e: Exception?): Boolean {
        return e is IOException && isBrokenPipe(e)
    }
}