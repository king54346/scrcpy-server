package com.genymobile.scrcpy.util

import android.os.Build
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.genymobile.scrcpy.AndroidVersions
import com.genymobile.scrcpy.BuildConfig
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
        if (Build.VERSION.SDK_INT >= AndroidVersions.API_23_ANDROID_6_0) {
            while (from.hasRemaining()) {
                write(fd, from)
            }
        } else {
            // ByteBuffer position is not updated as expected by Os.write() on old Android versions, so
            // handle the position and the remaining bytes manually.
            // See <https://github.com/Genymobile/scrcpy/issues/291>.
            var position = from.position()
            var remaining = from.remaining()
            while (remaining > 0) {
                val w = write(fd, from)
                if (BuildConfig.DEBUG && w < 0) {
                    // w should not be negative, since an exception is thrown on error
                    throw AssertionError("Os.write() returned a negative value ($w)")
                }
                remaining -= w
                position += w
                from.position(position)
            }
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