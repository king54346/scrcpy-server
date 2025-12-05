package com.genymobile.scrcpy.control

import com.genymobile.scrcpy.device.Position

/**
 * Union of all supported event types, identified by their `type`.
 */
class ControlMessage private constructor() {
    var type: Int = 0
        private set
    var text: String = ""
        private set
    var metaState: Int = 0 // KeyEvent.META_*
        private set
    var action: Int = 0 // KeyEvent.ACTION_* or MotionEvent.ACTION_*
        private set
    var keycode: Int = 0 // KeyEvent.KEYCODE_*
        private set
    var actionButton: Int = 0 // MotionEvent.BUTTON_*
        private set
    var buttons: Int = 0 // MotionEvent.BUTTON_*
        private set
    var pointerId: Long = 0
        private set
    var pressure: Float = 0f
        private set
    var position: Position? = null
        private set
    var hScroll: Float = 0f
        private set
    var vScroll: Float = 0f
        private set
    var copyKey: Int = 0
        private set
    var paste: Boolean = false
        private set
    var repeat: Int = 0
        private set
    var sequence: Long = 0
        private set
    var id: Int = 0
        private set
    lateinit var data: ByteArray
        private set
    var on: Boolean = false
        private set
    var vendorId: Int = 0
        private set
    var productId: Int = 0
        private set

    companion object {
        const val TYPE_INJECT_KEYCODE: Int = 0
        const val TYPE_INJECT_TEXT: Int = 1
        const val TYPE_INJECT_TOUCH_EVENT: Int = 2
        const val TYPE_INJECT_SCROLL_EVENT: Int = 3
        const val TYPE_BACK_OR_SCREEN_ON: Int = 4
        const val TYPE_EXPAND_NOTIFICATION_PANEL: Int = 5
        const val TYPE_EXPAND_SETTINGS_PANEL: Int = 6
        const val TYPE_COLLAPSE_PANELS: Int = 7
        const val TYPE_GET_CLIPBOARD: Int = 8
        const val TYPE_SET_CLIPBOARD: Int = 9
        const val TYPE_SET_DISPLAY_POWER: Int = 10
        const val TYPE_ROTATE_DEVICE: Int = 11
        const val TYPE_UHID_CREATE: Int = 12
        const val TYPE_UHID_INPUT: Int = 13
        const val TYPE_UHID_DESTROY: Int = 14
        const val TYPE_OPEN_HARD_KEYBOARD_SETTINGS: Int = 15
        const val TYPE_START_APP: Int = 16
        const val TYPE_RESET_VIDEO: Int = 17

        const val SEQUENCE_INVALID: Long = 0

        const val COPY_KEY_NONE: Int = 0
        const val COPY_KEY_COPY: Int = 1
        const val COPY_KEY_CUT: Int = 2

        fun createInjectKeycode(
            action: Int,
            keycode: Int,
            repeat: Int,
            metaState: Int
        ): ControlMessage {
            val msg = ControlMessage()
            msg.type = TYPE_INJECT_KEYCODE
            msg.action = action
            msg.keycode = keycode
            msg.repeat = repeat
            msg.metaState = metaState
            return msg
        }

        fun createInjectText(text: String): ControlMessage {
            val msg = ControlMessage()
            msg.type = TYPE_INJECT_TEXT
            msg.text = text
            return msg
        }

        fun createInjectTouchEvent(
            action: Int, pointerId: Long, position: Position?, pressure: Float, actionButton: Int,
            buttons: Int
        ): ControlMessage {
            val msg = ControlMessage()
            msg.type = TYPE_INJECT_TOUCH_EVENT
            msg.action = action
            msg.pointerId = pointerId
            msg.pressure = pressure
            msg.position = position
            msg.actionButton = actionButton
            msg.buttons = buttons
            return msg
        }

        fun createInjectScrollEvent(
            position: Position?,
            hScroll: Float,
            vScroll: Float,
            buttons: Int
        ): ControlMessage {
            val msg = ControlMessage()
            msg.type = TYPE_INJECT_SCROLL_EVENT
            msg.position = position
            msg.hScroll = hScroll
            msg.vScroll = vScroll
            msg.buttons = buttons
            return msg
        }

        fun createBackOrScreenOn(action: Int): ControlMessage {
            val msg = ControlMessage()
            msg.type = TYPE_BACK_OR_SCREEN_ON
            msg.action = action
            return msg
        }

        fun createGetClipboard(copyKey: Int): ControlMessage {
            val msg = ControlMessage()
            msg.type = TYPE_GET_CLIPBOARD
            msg.copyKey = copyKey
            return msg
        }

        fun createSetClipboard(sequence: Long, text: String, paste: Boolean): ControlMessage {
            val msg = ControlMessage()
            msg.type = TYPE_SET_CLIPBOARD
            msg.sequence = sequence
            msg.text = text
            msg.paste = paste
            return msg
        }

        fun createSetDisplayPower(on: Boolean): ControlMessage {
            val msg = ControlMessage()
            msg.type = TYPE_SET_DISPLAY_POWER
            msg.on = on
            return msg
        }

        fun createEmpty(type: Int): ControlMessage {
            val msg = ControlMessage()
            msg.type = type
            return msg
        }

        fun createUhidCreate(
            id: Int,
            vendorId: Int,
            productId: Int,
            name: String,
            reportDesc: ByteArray
        ): ControlMessage {
            val msg = ControlMessage()
            msg.type = TYPE_UHID_CREATE
            msg.id = id
            msg.vendorId = vendorId
            msg.productId = productId
            msg.text = name
            msg.data = reportDesc
            return msg
        }

        fun createUhidInput(id: Int, data: ByteArray): ControlMessage {
            val msg = ControlMessage()
            msg.type = TYPE_UHID_INPUT
            msg.id = id
            msg.data = data
            return msg
        }

        fun createUhidDestroy(id: Int): ControlMessage {
            val msg = ControlMessage()
            msg.type = TYPE_UHID_DESTROY
            msg.id = id
            return msg
        }

        fun createStartApp(name: String): ControlMessage {
            val msg = ControlMessage()
            msg.type = TYPE_START_APP
            msg.text = name
            return msg
        }
    }
}