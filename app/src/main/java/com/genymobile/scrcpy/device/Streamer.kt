package com.genymobile.scrcpy.device

import android.media.MediaCodec
import com.genymobile.scrcpy.audio.AudioCodec
import com.genymobile.scrcpy.util.Codec
import com.genymobile.scrcpy.util.IO
import java.io.FileDescriptor
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 音视频流发送器
 *
 * 负责将编码后的音视频数据通过文件描述符发送到客户端
 * 支持发送编解码器元数据、帧元数据和实际的媒体数据包
 *
 * @param fd 目标文件描述符（通常是网络 socket）
 * @param codec 使用的编解码器类型
 * @param sendCodecMeta 是否发送编解码器元数据（如编解码器 ID、视频尺寸等）
 * @param sendFrameMeta 是否发送帧元数据（如 PTS、关键帧标志等）
 */
class Streamer(
    private val fd: FileDescriptor,
    val codec: Codec,
    private val sendCodecMeta: Boolean,
    private val sendFrameMeta: Boolean
) {
    // 预分配的头部缓冲区，用于写入帧元数据（8字节PTS+标志 + 4字节包大小）
    private val headerBuffer: ByteBuffer = ByteBuffer.allocate(HEADER_SIZE)

    /**
     * 发送音频流的初始化头部
     * 如果启用了 sendCodecMeta，则发送 4 字节的编解码器 ID
     */
    @Throws(IOException::class)
    fun writeAudioHeader() {
        if (sendCodecMeta) {
            val buffer = ByteBuffer.allocate(Int.SIZE_BYTES)
            buffer.putInt(codec.id)
            buffer.flip()
            IO.writeFully(fd, buffer)
        }
    }

    /**
     * 发送视频流的初始化头部
     * 如果启用了 sendCodecMeta，则发送 12 字节：编解码器 ID + 宽度 + 高度
     *
     * @param videoSize 视频尺寸
     */
    @Throws(IOException::class)
    fun writeVideoHeader(videoSize: Size) {
        if (sendCodecMeta) {
            val buffer = ByteBuffer.allocate(VIDEO_HEADER_SIZE)
            buffer.putInt(codec.id)
            buffer.putInt(videoSize.width)
            buffer.putInt(videoSize.height)
            buffer.flip()
            IO.writeFully(fd, buffer)
        }
    }

    /**
     * 发送禁用流的信号
     *
     * 通过发送特殊的编解码器 ID 来通知客户端停止流传输：
     * - code 0: 正常禁用流（例如音频采集失败，但可以继续镜像视频）
     * - code 1: 发生配置错误，客户端必须停止
     *
     * @param error true 表示错误禁用（code 1），false 表示正常禁用（code 0）
     */
    @Throws(IOException::class)
    fun writeDisableStream(error: Boolean) {
        val code = ByteArray(Int.SIZE_BYTES)
        if (error) {
            code[3] = DISABLE_STREAM_ERROR_CODE
        }
        IO.writeFully(fd, code, 0, code.size)
    }

    /**
     * 发送一个媒体数据包
     *
     * @param buffer 媒体数据缓冲区
     * @param pts 显示时间戳（Presentation Time Stamp），单位：微秒
     * @param config 是否为配置包（如 SPS/PPS）
     * @param keyFrame 是否为关键帧
     */
    @Throws(IOException::class)
    fun writePacket(buffer: ByteBuffer, pts: Long, config: Boolean, keyFrame: Boolean) {
        // 针对特定编解码器修正配置包格式
        if (config) {
            when (codec) {
                AudioCodec.OPUS -> fixOpusConfigPacket(buffer)
                AudioCodec.FLAC -> fixFlacConfigPacket(buffer)
            }
        }

        // 如果启用帧元数据，先发送元数据
        if (sendFrameMeta) {
            writeFrameMeta(fd, buffer.remaining(), pts, config, keyFrame)
        }

        // 发送实际的媒体数据
        IO.writeFully(fd, buffer)
    }

    /**
     * 从 MediaCodec 的 BufferInfo 发送数据包
     * 这是一个便捷方法，自动从 BufferInfo 提取 PTS 和标志位
     *
     * @param codecBuffer 编解码器输出缓冲区
     * @param bufferInfo 缓冲区信息，包含 PTS 和标志位
     */
    @Throws(IOException::class)
    fun writePacket(codecBuffer: ByteBuffer, bufferInfo: MediaCodec.BufferInfo) {
        val pts = bufferInfo.presentationTimeUs
        val config = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
        val keyFrame = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
        writePacket(codecBuffer, pts, config, keyFrame)
    }

    /**
     * 写入帧元数据（12 字节）
     *
     * 格式：
     * - 8 字节：PTS（或标志位）
     *   - 如果是配置包：最高位设为 1 (PACKET_FLAG_CONFIG)
     *   - 如果是关键帧：次高位设为 1 (PACKET_FLAG_KEY_FRAME)
     * - 4 字节：数据包大小
     *
     * @param fd 文件描述符
     * @param packetSize 数据包大小（字节）
     * @param pts 显示时间戳
     * @param config 是否为配置包
     * @param keyFrame 是否为关键帧
     */
    @Throws(IOException::class)
    private fun writeFrameMeta(
        fd: FileDescriptor,
        packetSize: Int,
        pts: Long,
        config: Boolean,
        keyFrame: Boolean
    ) {
        headerBuffer.clear()

        // 构造 PTS 和标志位
        val ptsAndFlags: Long = if (config) {
            // 配置包：使用特殊标志而不是 PTS
            PACKET_FLAG_CONFIG
        } else {
            // 普通帧：使用 PTS，并可能添加关键帧标志
            var flags = pts
            if (keyFrame) {
                flags = flags or PACKET_FLAG_KEY_FRAME
            }
            flags
        }

        headerBuffer.putLong(ptsAndFlags)
        headerBuffer.putInt(packetSize)
        headerBuffer.flip()
        IO.writeFully(fd, headerBuffer)
    }

    companion object {
        // 帧元数据大小：8字节（PTS+标志）+ 4字节（包大小）
        private const val HEADER_SIZE = 12

        // 视频头部大小：4字节（codec ID）+ 4字节（宽度）+ 4字节（高度）
        private const val VIDEO_HEADER_SIZE = 12

        // 禁用流错误代码
        private const val DISABLE_STREAM_ERROR_CODE: Byte = 1

        // 配置包标志：将 PTS 的最高位设为 1
        private const val PACKET_FLAG_CONFIG = 1L shl 63

        // 关键帧标志：将 PTS 的次高位设为 1
        private const val PACKET_FLAG_KEY_FRAME = 1L shl 62

        /**
         * 修正 OPUS 配置包格式
         *
         * Android MediaCodec 输出的 OPUS 配置包包含多个部分，每个部分都有 ID 和长度前缀。
         * 我们只需要 "AOPUSHDR" 部分的实际 OpusHead 数据作为 extradata。
         *
         * 原始格式：
         * ```
         * 00000000  41 4f 50 55 53 48 44 52  13 00 00 00 00 00 00 00  |AOPUSHDR........|
         *           ^------ 8字节 ID ------^  ^------ 8字节长度 -----^
         * 00000010  4f 70 75 73 48 65 61 64  01 01 38 01 80 bb 00 00  |OpusHead..8.....|
         *           ^-------------- 实际的 OpusHead 数据 --------------^
         * ```
         *
         * @param buffer 包含 OPUS 配置数据的缓冲区，将被修改为只包含 OpusHead 部分
         */
        @Throws(IOException::class)
        private fun fixOpusConfigPacket(buffer: ByteBuffer) {
            if (buffer.remaining() < OPUS_HEADER_PREFIX_SIZE) {
                throw IOException("Not enough data in OPUS config packet")
            }

            // 验证 "AOPUSHDR" 标识符
            val opusHeaderId = "AOPUSHDR".toByteArray()
            val idBuffer = ByteArray(OPUS_ID_SIZE)
            buffer.get(idBuffer)

            if (!idBuffer.contentEquals(opusHeaderId)) {
                throw IOException("OPUS header not found")
            }

            // 读取数据块大小（本地字节序）
            val sizeLong = buffer.getLong()
            validateBlockSize(sizeLong, "OPUS")

            val size = sizeLong.toInt()
            if (buffer.remaining() < size) {
                throw IOException("Not enough data in OPUS header (invalid size: $size)")
            }

            // 将缓冲区限制设置为只包含 OpusHead 数据
            buffer.limit(buffer.position() + size)
        }

        /**
         * 修正 FLAC 配置包格式
         *
         * Android MediaCodec 输出的 FLAC 配置包包含 "fLaC" 魔数、大小和元数据块。
         * 我们只需要元数据块部分作为 extradata。
         *
         * 原始格式：
         * ```
         * 00000000  66 4c 61 43 00 00 00 22  |fLaC..."        |
         *           ^-- 4字节ID-^  ^-4字节大小-^
         * 00000008  10 00 10 00 00 00 00 00  |........        |
         *           ^-------- STREAMINFO 块 --------^
         * ```
         *
         * @param buffer 包含 FLAC 配置数据的缓冲区，将被修改为只包含 STREAMINFO 部分
         */
        @Throws(IOException::class)
        private fun fixFlacConfigPacket(buffer: ByteBuffer) {
            if (buffer.remaining() < FLAC_HEADER_PREFIX_SIZE) {
                throw IOException("Not enough data in FLAC config packet")
            }

            // 验证 "fLaC" 魔数
            val flacHeaderId = "fLaC".toByteArray()
            val idBuffer = ByteArray(FLAC_ID_SIZE)
            buffer.get(idBuffer)

            if (!idBuffer.contentEquals(flacHeaderId)) {
                throw IOException("FLAC header not found")
            }

            // FLAC 使用大端字节序
            buffer.order(ByteOrder.BIG_ENDIAN)

            // 读取元数据块大小
            val size = buffer.getInt()
            if (buffer.remaining() < size) {
                throw IOException("Not enough data in FLAC header (invalid size: $size)")
            }

            // 将缓冲区限制设置为只包含 STREAMINFO 块
            buffer.limit(buffer.position() + size)
        }

        /**
         * 验证数据块大小的有效性
         *
         * @param size 要验证的大小
         * @param codecName 编解码器名称（用于错误消息）
         */
        @Throws(IOException::class)
        private fun validateBlockSize(size: Long, codecName: String) {
            if (size < 0 || size >= Int.MAX_VALUE) {
                throw IOException("Invalid block size in $codecName header: $size")
            }
        }

        // OPUS 相关常量
        private const val OPUS_ID_SIZE = 8
        private const val OPUS_HEADER_PREFIX_SIZE = 16  // 8字节ID + 8字节大小

        // FLAC 相关常量
        private const val FLAC_ID_SIZE = 4
        private const val FLAC_HEADER_PREFIX_SIZE = 8   // 4字节ID + 4字节大小
    }
}