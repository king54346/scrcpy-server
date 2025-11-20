package com.genymobile.scrcpy.control

import android.os.Build
import android.os.HandlerThread
import android.os.MessageQueue
import android.os.MessageQueue.OnFileDescriptorEventListener
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.ArrayMap
import com.genymobile.scrcpy.AndroidVersions
import com.genymobile.scrcpy.util.Ln.e
import com.genymobile.scrcpy.util.Ln.w
import com.genymobile.scrcpy.util.StringUtils.getUtf8TruncationIndex
import com.genymobile.scrcpy.wrappers.ServiceManager.inputManager
import java.io.FileDescriptor
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

class UhidManager(private val sender: DeviceMessageSender, private val displayUniqueId: String?) {
    private val fds = ArrayMap<Int, FileDescriptor>()
    private val buffer: ByteBuffer =
        ByteBuffer.allocate(SIZE_OF_UHID_EVENT).order(ByteOrder.nativeOrder())

    private var queue: MessageQueue? = null

    init {
        val thread = HandlerThread("UHidManager")
        thread.start()
        queue = thread.looper.queue
    }

    @Throws(IOException::class)
    fun open(id: Int, vendorId: Int, productId: Int, name: String, reportDesc: ByteArray) {
        try {
            val fd = Os.open("/dev/uhid", OsConstants.O_RDWR, 0)
            try {
                // First UHID device added
                val firstDevice = fds.isEmpty()

                val old = fds.put(id, fd)
                if (old != null) {
                    w("Duplicate UHID id: $id")
                    close(old)
                }

                val phys = if (mustUseInputPort()) INPUT_PORT else null
                val req = buildUhidCreate2Req(vendorId, productId, name, reportDesc, phys)
                Os.write(fd, req, 0, req.size)

                if (firstDevice) {
                    addUniqueIdAssociation()
                }
                registerUhidListener(id, fd)
            } catch (e: Exception) {
                close(fd)
                throw e
            }
        } catch (e: ErrnoException) {
            throw IOException(e)
        }
    }

    private fun registerUhidListener(id: Int, fd: FileDescriptor) {
        queue!!.addOnFileDescriptorEventListener(
            fd, OnFileDescriptorEventListener.EVENT_INPUT
        ) { fd2: FileDescriptor?, events: Int ->
            try {
                buffer.clear()
                val r = Os.read(fd2, buffer)
                buffer.flip()
                if (r > 0) {
                    val type = buffer.getInt()
                    if (type == UHID_OUTPUT) {
                        val data = extractHidOutputData(buffer)
                        if (data != null) {
                            val msg = DeviceMessage.createUhidOutput(id, data)
                            sender.send(msg)
                        }
                    }
                }
            } catch (e: ErrnoException) {
                e("Failed to read UHID output", e)
                return@addOnFileDescriptorEventListener 0
            } catch (e: InterruptedIOException) {
                e("Failed to read UHID output", e)
                return@addOnFileDescriptorEventListener 0
            }
            events
        }
    }

    private fun unregisterUhidListener(fd: FileDescriptor) {
        queue!!.removeOnFileDescriptorEventListener(fd)
    }

    @Throws(IOException::class)
    fun writeInput(id: Int, data: ByteArray) {
        val fd = fds[id]
        if (fd == null) {
            w("Unknown UHID id: $id")
            return
        }

        try {
            val req = buildUhidInput2Req(data)
            Os.write(fd, req, 0, req.size)
        } catch (e: ErrnoException) {
            throw IOException(e)
        }
    }

    fun close(id: Int) {
        // Linux: Documentation/hid/uhid.rst
        // If you close() the fd, the device is automatically unregistered and destroyed internally.
        val fd = fds.remove(id)
        if (fd != null) {
            unregisterUhidListener(fd)
            close(fd)

            if (fds.isEmpty()) {
                // Last UHID device removed
                removeUniqueIdAssociation()
            }
        } else {
            w("Closing unknown UHID device: $id")
        }
    }

    fun closeAll() {
        if (fds.isEmpty()) {
            return
        }

        for (fd in fds.values) {
            close(fd)
        }

        removeUniqueIdAssociation()
    }

    private fun mustUseInputPort(): Boolean {
        return Build.VERSION.SDK_INT >= AndroidVersions.API_35_ANDROID_15 && displayUniqueId != null
    }

    private fun addUniqueIdAssociation() {
        if (mustUseInputPort()) {
            inputManager!!.addUniqueIdAssociationByPort(
                INPUT_PORT,
                displayUniqueId!!
            )
        }
    }

    private fun removeUniqueIdAssociation() {
        if (mustUseInputPort()) {
            inputManager!!.removeUniqueIdAssociationByPort(INPUT_PORT)
        }
    }

    companion object {
        // Linux: include/uapi/linux/uhid.h
        private const val UHID_OUTPUT = 6
        private const val UHID_CREATE2 = 11
        private const val UHID_INPUT2 = 12

        // Linux: include/uapi/linux/input.h
        private const val BUS_VIRTUAL: Short = 0x06

        private const val SIZE_OF_UHID_EVENT = 4380 // sizeof(struct uhid_event)

        // Must be unique across the system
        private val INPUT_PORT = "scrcpy:" + Os.getpid()

        private fun extractHidOutputData(buffer: ByteBuffer): ByteArray? {
            /*
         * #define UHID_DATA_MAX 4096
         * struct uhid_event {
         *     uint32_t type;
         *     union {
         *         // ...
         *         struct uhid_output_req {
         *             __u8 data[UHID_DATA_MAX];
         *             __u16 size;
         *             __u8 rtype;
         *         };
         *     };
         * } __attribute__((__packed__));
         */

            if (buffer.remaining() < 4099) {
                w("Incomplete HID output")
                return null
            }
            val size = buffer.getShort(buffer.position() + 4096).toInt() and 0xFFFF
            if (size > 4096) {
                w("Incorrect HID output size: $size")
                return null
            }
            val data = ByteArray(size)
            buffer[data]
            return data
        }

        private fun buildUhidCreate2Req(
            vendorId: Int,
            productId: Int,
            name: String,
            reportDesc: ByteArray,
            phys: String?
        ): ByteArray {
            /*
         * struct uhid_event {
         *     uint32_t type;
         *     union {
         *         // ...
         *         struct uhid_create2_req {
         *             uint8_t name[128];
         *             uint8_t phys[64];
         *             uint8_t uniq[64];
         *             uint16_t rd_size;
         *             uint16_t bus;
         *             uint32_t vendor;
         *             uint32_t product;
         *             uint32_t version;
         *             uint32_t country;
         *             uint8_t rd_data[HID_MAX_DESCRIPTOR_SIZE];
         *         };
         *     };
         * } __attribute__((__packed__));
         */

            val buf = ByteBuffer.allocate(280 + reportDesc.size).order(ByteOrder.nativeOrder())
            buf.putInt(UHID_CREATE2)

            val actualName = if (name.isEmpty()) "scrcpy" else name
            val nameBytes = actualName.toByteArray(StandardCharsets.UTF_8)
            val nameLen = getUtf8TruncationIndex(nameBytes, 127)
            assert(nameLen <= 127)
            buf.put(nameBytes, 0, nameLen)

            if (phys != null) {
                buf.position(4 + 128)
                val physBytes = phys.toByteArray(StandardCharsets.US_ASCII)
                assert(physBytes.size <= 63)
                buf.put(physBytes)
            }

            buf.position(4 + 256)
            buf.putShort(reportDesc.size.toShort())
            buf.putShort(BUS_VIRTUAL)
            buf.putInt(vendorId)
            buf.putInt(productId)
            buf.putInt(0) // version
            buf.putInt(0) // country;
            buf.put(reportDesc)
            return buf.array()
        }

        private fun buildUhidInput2Req(data: ByteArray): ByteArray {
            /*
         * struct uhid_event {
         *     uint32_t type;
         *     union {
         *         // ...
         *         struct uhid_input2_req {
         *             uint16_t size;
         *             uint8_t data[UHID_DATA_MAX];
         *         };
         *     };
         * } __attribute__((__packed__));
         */

            val buf = ByteBuffer.allocate(6 + data.size).order(ByteOrder.nativeOrder())
            buf.putInt(UHID_INPUT2)
            buf.putShort(data.size.toShort())
            buf.put(data)
            return buf.array()
        }

        private fun close(fd: FileDescriptor) {
            try {
                Os.close(fd)
            } catch (e: ErrnoException) {
                e("Failed to close uhid: " + e.message)
            }
        }
    }
}