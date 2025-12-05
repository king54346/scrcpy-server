package com.genymobile.scrcpy.control

import com.genymobile.scrcpy.device.Position
import com.genymobile.scrcpy.util.Binary.i16FixedPointToFloat
import com.genymobile.scrcpy.util.Binary.u16FixedPointToFloat
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets

class ControlMessageReader(rawInputStream: InputStream) {
    private val dis = DataInputStream(BufferedInputStream(rawInputStream))

    @Throws(IOException::class)
    fun read(): ControlMessage {
        val type = dis.readUnsignedByte()
        return when (type) {
            ControlMessage.TYPE_INJECT_KEYCODE -> parseInjectKeycode()
            ControlMessage.TYPE_INJECT_TEXT -> parseInjectText()
            ControlMessage.TYPE_INJECT_TOUCH_EVENT -> parseInjectTouchEvent()
            ControlMessage.TYPE_INJECT_SCROLL_EVENT -> parseInjectScrollEvent()
            ControlMessage.TYPE_BACK_OR_SCREEN_ON -> parseBackOrScreenOnEvent()
            ControlMessage.TYPE_GET_CLIPBOARD -> parseGetClipboard()
            ControlMessage.TYPE_SET_CLIPBOARD -> parseSetClipboard()
            ControlMessage.TYPE_SET_DISPLAY_POWER -> parseSetDisplayPower()
            ControlMessage.TYPE_EXPAND_NOTIFICATION_PANEL, ControlMessage.TYPE_EXPAND_SETTINGS_PANEL, ControlMessage.TYPE_COLLAPSE_PANELS, ControlMessage.TYPE_ROTATE_DEVICE, ControlMessage.TYPE_OPEN_HARD_KEYBOARD_SETTINGS, ControlMessage.TYPE_RESET_VIDEO -> ControlMessage.createEmpty(
                type
            )

            ControlMessage.TYPE_UHID_CREATE -> parseUhidCreate()
            ControlMessage.TYPE_UHID_INPUT -> parseUhidInput()
            ControlMessage.TYPE_UHID_DESTROY -> parseUhidDestroy()
            ControlMessage.TYPE_START_APP -> parseStartApp()
            else -> throw ControlProtocolException("Unknown event type: $type")
        }
    }

    @Throws(IOException::class)
    private fun parseInjectKeycode(): ControlMessage {
        val action = dis.readUnsignedByte()
        val keycode = dis.readInt()
        val repeat = dis.readInt()
        val metaState = dis.readInt()
        return ControlMessage.createInjectKeycode(action, keycode, repeat, metaState)
    }

    @Throws(IOException::class)
    private fun parseBufferLength(sizeBytes: Int): Int {
        assert(sizeBytes > 0 && sizeBytes <= 4)
        var value = 0
        for (i in 0..<sizeBytes) {
            value = (value shl 8) or dis.readUnsignedByte()
        }
        return value
    }

    @Throws(IOException::class)
    private fun parseString(sizeBytes: Int = 4): String {
        assert(sizeBytes > 0 && sizeBytes <= 4)
        val data = parseByteArray(sizeBytes)
        return String(data, StandardCharsets.UTF_8)
    }

    @Throws(IOException::class)
    private fun parseByteArray(sizeBytes: Int): ByteArray {
        val len = parseBufferLength(sizeBytes)
        val data = ByteArray(len)
        dis.readFully(data)
        return data
    }

    @Throws(IOException::class)
    private fun parseInjectText(): ControlMessage {
        val text = parseString()
        return ControlMessage.createInjectText(text)
    }

    @Throws(IOException::class)
    private fun parseInjectTouchEvent(): ControlMessage {
        val action = dis.readUnsignedByte()
        val pointerId = dis.readLong()
        val position = parsePosition()
        val pressure = u16FixedPointToFloat(dis.readShort())
        val actionButton = dis.readInt()
        val buttons = dis.readInt()
        return ControlMessage.createInjectTouchEvent(
            action,
            pointerId,
            position,
            pressure,
            actionButton,
            buttons
        )
    }

    @Throws(IOException::class)
    private fun parseInjectScrollEvent(): ControlMessage {
        val position = parsePosition()
        // Binary.i16FixedPointToFloat() decodes values assuming the full range is [-1, 1], but the actual range is [-16, 16].
        val hScroll = i16FixedPointToFloat(dis.readShort()) * 16
        val vScroll = i16FixedPointToFloat(dis.readShort()) * 16
        val buttons = dis.readInt()
        return ControlMessage.createInjectScrollEvent(position, hScroll, vScroll, buttons)
    }

    @Throws(IOException::class)
    private fun parseBackOrScreenOnEvent(): ControlMessage {
        val action = dis.readUnsignedByte()
        return ControlMessage.createBackOrScreenOn(action)
    }

    @Throws(IOException::class)
    private fun parseGetClipboard(): ControlMessage {
        val copyKey = dis.readUnsignedByte()
        return ControlMessage.createGetClipboard(copyKey)
    }

    @Throws(IOException::class)
    private fun parseSetClipboard(): ControlMessage {
        val sequence = dis.readLong()
        val paste = dis.readByte().toInt() != 0
        val text = parseString()
        return ControlMessage.createSetClipboard(sequence, text, paste)
    }

    @Throws(IOException::class)
    private fun parseSetDisplayPower(): ControlMessage {
        val on = dis.readBoolean()
        return ControlMessage.createSetDisplayPower(on)
    }

    @Throws(IOException::class)
    private fun parseUhidCreate(): ControlMessage {
        val id = dis.readUnsignedShort()
        val vendorId = dis.readUnsignedShort()
        val productId = dis.readUnsignedShort()
        val name = parseString(1)
        val data = parseByteArray(2)
        return ControlMessage.createUhidCreate(id, vendorId, productId, name, data)
    }

    @Throws(IOException::class)
    private fun parseUhidInput(): ControlMessage {
        val id = dis.readUnsignedShort()
        val data = parseByteArray(2)
        return ControlMessage.createUhidInput(id, data)
    }

    @Throws(IOException::class)
    private fun parseUhidDestroy(): ControlMessage {
        val id = dis.readUnsignedShort()
        return ControlMessage.createUhidDestroy(id)
    }

    @Throws(IOException::class)
    private fun parseStartApp(): ControlMessage {
        val name = parseString(1)
        return ControlMessage.createStartApp(name)
    }

    @Throws(IOException::class)
    private fun parsePosition(): Position {
        val x = dis.readInt()
        val y = dis.readInt()
        val screenWidth = dis.readUnsignedShort()
        val screenHeight = dis.readUnsignedShort()
        return Position(x, y, screenWidth, screenHeight)
    }

    companion object {
        private const val MESSAGE_MAX_SIZE = 1 shl 18 // 256k

        const val CLIPBOARD_TEXT_MAX_LENGTH: Int =
            MESSAGE_MAX_SIZE - 14 // type: 1 byte; sequence: 8 bytes; paste flag: 1 byte; length: 4 bytes
        const val INJECT_TEXT_MAX_LENGTH: Int = 300
    }
}