package com.genymobile.scrcpy.control

import android.net.LocalSocket
import java.io.IOException

class ControlChannel(controlSocket: LocalSocket) {
    private val reader: ControlMessageReader
    private val writer: DeviceMessageWriter

    init {
        reader = ControlMessageReader(controlSocket.inputStream)
        writer = DeviceMessageWriter(controlSocket.outputStream)
    }

    @Throws(IOException::class)
    fun recv(): ControlMessage {
        return reader.read()
    }

    @Throws(IOException::class)
    fun send(msg: DeviceMessage) {
        writer.write(msg)
    }
}