package com.genymobile.scrcpy.control

class DeviceMessage private constructor() {
    var type: Int = 0
        private set
    var text: String? = null
        private set
    var sequence: Long = 0
        private set
    var id: Int = 0
        private set
    lateinit var data: ByteArray
        private set

    companion object {
        const val TYPE_CLIPBOARD: Int = 0
        const val TYPE_ACK_CLIPBOARD: Int = 1
        const val TYPE_UHID_OUTPUT: Int = 2

        fun createClipboard(text: String?): DeviceMessage {
            val event = DeviceMessage()
            event.type = TYPE_CLIPBOARD
            event.text = text
            return event
        }

        fun createAckClipboard(sequence: Long): DeviceMessage {
            val event = DeviceMessage()
            event.type = TYPE_ACK_CLIPBOARD
            event.sequence = sequence
            return event
        }

        fun createUhidOutput(id: Int, data: ByteArray): DeviceMessage {
            val event = DeviceMessage()
            event.type = TYPE_UHID_OUTPUT
            event.id = id
            event.data = data
            return event
        }
    }
}