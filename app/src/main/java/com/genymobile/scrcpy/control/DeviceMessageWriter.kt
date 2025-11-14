package com.genymobile.scrcpy.control

import com.genymobile.scrcpy.util.StringUtils.getUtf8TruncationIndex
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets

class DeviceMessageWriter(rawOutputStream: OutputStream) {
    private val dos = DataOutputStream(BufferedOutputStream(rawOutputStream))

    @Throws(IOException::class)
    fun write(msg: DeviceMessage) {
        val type = msg.type
        dos.writeByte(type)
        when (type) {
            DeviceMessage.TYPE_CLIPBOARD -> {
                val text = msg.text
                val raw = text!!.toByteArray(StandardCharsets.UTF_8)
                val len = getUtf8TruncationIndex(raw, CLIPBOARD_TEXT_MAX_LENGTH)
                dos.writeInt(len)
                dos.write(raw, 0, len)
            }

            DeviceMessage.TYPE_ACK_CLIPBOARD -> dos.writeLong(msg.sequence)
            DeviceMessage.TYPE_UHID_OUTPUT -> {
                dos.writeShort(msg.id)
                val data = msg.data
                dos.writeShort(data.size)
                dos.write(data)
            }

            else -> throw ControlProtocolException("Unknown event type: $type")
        }
        dos.flush()
    }

    companion object {
        private const val MESSAGE_MAX_SIZE = 1 shl 18 // 256k
        const val CLIPBOARD_TEXT_MAX_LENGTH: Int =
            MESSAGE_MAX_SIZE - 5 // type: 1 byte; length: 4 bytes
    }
}