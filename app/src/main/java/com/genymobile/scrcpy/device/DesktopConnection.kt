package com.genymobile.scrcpy.device

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import com.genymobile.scrcpy.control.ControlChannel
import com.genymobile.scrcpy.util.IO
import com.genymobile.scrcpy.util.Ln
import com.genymobile.scrcpy.util.StringUtils
import java.io.Closeable
import java.io.FileDescriptor
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * 桌面连接管理器
 *
 * 负责建立 Android 服务端与桌面客户端之间的通信通道。
 * 支持三种独立的通道:
 * - Video: 视频流传输
 * - Audio: 音频流传输
 * - Control: 控制指令传输 (触摸、键盘等)
 *
 * 连接模式:
 * 1. Normal Mode: Server 主动连接 Client (默认)
 * 2. Tunnel Forward Mode: Server 监听，Client 主动连接
 *
 * @property videoSocket 视频通道 socket
 * @property audioSocket 音频通道 socket
 * @property controlSocket 控制通道 socket
 */
class DesktopConnection private constructor(
    private val videoSocket: LocalSocket?,
    private val audioSocket: LocalSocket?,
    private val controlSocket: LocalSocket?
) : Closeable {

    /** 视频流文件描述符 */
    val videoFd: FileDescriptor? by lazy { videoSocket?.fileDescriptor }

    /** 音频流文件描述符 */
    val audioFd: FileDescriptor? by lazy { audioSocket?.fileDescriptor }

    /** 控制通道 */
    val controlChannel: ControlChannel? by lazy { controlSocket?.let(::ControlChannel) }

    /** 第一个可用的 socket */
    private val firstSocket: LocalSocket?
        get() = videoSocket ?: audioSocket ?: controlSocket

    /**
     * 关闭所有 socket 的 I/O 流
     */
    @Throws(IOException::class)
    fun shutdown() {
        listOf(
            videoSocket to "video",
            audioSocket to "audio",
            controlSocket to "control"
        ).forEach { (socket, name) ->
            socket?.runCatching {
                shutdownInput()
                shutdownOutput()
            }?.onFailure { e ->
                Ln.w("Failed to shutdown $name socket", e)
            }
        }
    }

    /**
     * 关闭所有 socket 连接
     */
    override fun close() {
        sequenceOf(videoSocket, audioSocket, controlSocket)
            .filterNotNull()
            .forEach { it.closeQuietly() }
    }

    /**
     * 发送设备元数据给客户端
     *
     * @param deviceName 设备名称 (如 "Samsung Galaxy S23")
     * @throws IOException 如果发送失败
     */
    @Throws(IOException::class)
    fun sendDeviceMeta(deviceName: String) {
        val socket = firstSocket
            ?: throw IOException("No socket available to send device meta")

        val buffer = deviceName.toDeviceNameBuffer()
        IO.writeFully(socket.fileDescriptor, buffer, 0, buffer.size)
    }

    companion object {
        /**
         * 建立桌面连接
         *
         * @param scid Session ID (用于多实例支持)
         * @param tunnelForward 是否使用隧道转发模式
         * @param video 是否启用视频流
         * @param audio 是否启用音频流
         * @param control 是否启用控制通道
         * @param sendDummyByte 是否发送 dummy byte (用于连接确认)
         * @return 建立的连接
         * @throws IOException 如果连接失败
         */
        @JvmStatic
        @Throws(IOException::class)
        fun open(
            scid: Int,
            tunnelForward: Boolean,
            video: Boolean,
            audio: Boolean,
            control: Boolean,
            sendDummyByte: Boolean
        ): DesktopConnection {
            val socketName = scid.toSocketName()

            return if (tunnelForward) {
                openTunnelForward(socketName, video, audio, control, sendDummyByte)
            } else {
                openNormalMode(socketName, video, audio, control)
            }
        }

        /**
         * Normal Mode: Server 主动连接到 Client
         */
        @Throws(IOException::class)
        private fun openNormalMode(
            socketName: String,
            video: Boolean,
            audio: Boolean,
            control: Boolean
        ): DesktopConnection = runCatching {
            DesktopConnection(
                videoSocket = if (video) socketName.connectToSocket() else null,
                audioSocket = if (audio) socketName.connectToSocket() else null,
                controlSocket = if (control) socketName.connectToSocket() else null
            )
        }.getOrElse { e ->
            throw IOException("Failed to connect in normal mode", e)
        }

        /**
         * Tunnel Forward Mode: Server 监听，Client 主动连接
         */
        @Throws(IOException::class)
        private fun openTunnelForward(
            socketName: String,
            video: Boolean,
            audio: Boolean,
            control: Boolean,
            sendDummyByte: Boolean
        ): DesktopConnection = LocalServerSocket(socketName).use { serverSocket ->
            val dummySender = if (sendDummyByte) DummyByteSender() else null

            val videoSocket = if (video) {
                serverSocket.accept().also { dummySender?.sendTo(it, "video") }
            } else null

            val audioSocket = if (audio) {
                serverSocket.accept().also { dummySender?.sendTo(it, "audio") }
            } else null

            val controlSocket = if (control) {
                serverSocket.accept().also { dummySender?.sendTo(it, "control") }
            } else null

            DesktopConnection(videoSocket, audioSocket, controlSocket)
        }
    }
}

// ============================================
// Kotlin 扩展函数
// ============================================

/**
 * 安全关闭 LocalSocket (忽略异常)
 */
private fun LocalSocket.closeQuietly() {
    runCatching { close() }
}

/**
 * 自动资源管理
 */
private inline fun <T> LocalServerSocket.use(block: (LocalServerSocket) -> T): T {
    return try {
        block(this)
    } finally {
        runCatching { close() }
    }
}

/**
 * 连接到指定的 LocalSocket
 */
@Throws(IOException::class)
private fun String.connectToSocket(): LocalSocket = LocalSocket().apply {
    connect(LocalSocketAddress(this@connectToSocket))
}

/**
 * 将设备名称转换为固定长度的缓冲区
 */
private fun String.toDeviceNameBuffer(): ByteArray {
    val buffer = ByteArray(DEVICE_NAME_FIELD_LENGTH)
    val bytes = toByteArray(StandardCharsets.UTF_8)
    val len = StringUtils.getUtf8TruncationIndex(
        bytes,
        DEVICE_NAME_FIELD_LENGTH - 1
    )
    System.arraycopy(bytes, 0, buffer, 0, len)
    return buffer
}

/**
 * 获取 socket 名称
 */
private fun Int.toSocketName(): String = when (this) {
    -1 -> SOCKET_NAME_PREFIX
    else -> "${SOCKET_NAME_PREFIX}_${String.format("%08x", this)}"
}

/**
 * Dummy Byte 发送器
 * 用于在 Tunnel Forward 模式下向客户端发送确认字节
 *
 * 作用: 解决 LocalSocket 连接的竞态问题，确保客户端知道连接已建立
 */
private class DummyByteSender {
    private var sent = false

    /**
     * 向 socket 发送 dummy byte (只发送一次)
     */
    fun sendTo(socket: LocalSocket, channelName: String) {
        if (sent) return

        runCatching {
            socket.outputStream.apply {
                write(DUMMY_BYTE.toInt())
                flush()
            }
            sent = true
            Ln.d("Sent dummy byte to $channelName channel")
        }.onFailure { e ->
            Ln.w("Failed to send dummy byte to $channelName", e)
            throw e
        }
    }
}

// ============================================
// 文件级别常量 (在扩展函数中使用)
// ============================================

private const val DEVICE_NAME_FIELD_LENGTH = 64
private const val SOCKET_NAME_PREFIX = "scrcpy"
private const val DUMMY_BYTE: Byte = 0