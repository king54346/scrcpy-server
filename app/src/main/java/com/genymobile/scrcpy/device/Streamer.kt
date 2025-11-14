package com.genymobile.scrcpy.device

import android.media.MediaCodec
import com.genymobile.scrcpy.audio.AudioCodec
import com.genymobile.scrcpy.util.Codec
import com.genymobile.scrcpy.util.IO
import java.io.FileDescriptor
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Streamer(
    private val fd: FileDescriptor,
    val codec: Codec,
    private val sendCodecMeta: Boolean,
    private val sendFrameMeta: Boolean
) {
    private val headerBuffer: ByteBuffer = ByteBuffer.allocate(12)

    @Throws(IOException::class)
    fun writeAudioHeader() {
        if (sendCodecMeta) {
            val buffer = ByteBuffer.allocate(4)
            buffer.putInt(codec.id)
            buffer.flip()
            IO.writeFully(fd, buffer)
        }
    }

    @Throws(IOException::class)
    fun writeVideoHeader(videoSize: Size) {
        if (sendCodecMeta) {
            val buffer = ByteBuffer.allocate(12)
            buffer.putInt(codec.id)
            buffer.putInt(videoSize.width)
            buffer.putInt(videoSize.height)
            buffer.flip()
            IO.writeFully(fd, buffer)
        }
    }

    @Throws(IOException::class)
    fun writeDisableStream(error: Boolean) {
        // Writing a specific code as codec-id means that the device disables the stream
        //   code 0: it explicitly disables the stream (because it could not capture audio), scrcpy should continue mirroring video only
        //   code 1: a configuration error occurred, scrcpy must be stopped
        val code = ByteArray(4)
        if (error) {
            code[3] = 1
        }
        IO.writeFully(fd, code, 0, code.size)
    }

    @Throws(IOException::class)
    fun writePacket(buffer: ByteBuffer, pts: Long, config: Boolean, keyFrame: Boolean) {
        if (config) {
            if (codec === AudioCodec.OPUS) {
                fixOpusConfigPacket(buffer)
            } else if (codec === AudioCodec.FLAC) {
                fixFlacConfigPacket(buffer)
            }
        }

        if (sendFrameMeta) {
            writeFrameMeta(fd, buffer.remaining(), pts, config, keyFrame)
        }

        IO.writeFully(fd, buffer)
    }

    @Throws(IOException::class)
    fun writePacket(codecBuffer: ByteBuffer, bufferInfo: MediaCodec.BufferInfo) {
        val pts = bufferInfo.presentationTimeUs
        val config = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
        val keyFrame = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
        writePacket(codecBuffer, pts, config, keyFrame)
    }

    @Throws(IOException::class)
    private fun writeFrameMeta(
        fd: FileDescriptor,
        packetSize: Int,
        pts: Long,
        config: Boolean,
        keyFrame: Boolean
    ) {
        headerBuffer.clear()

        var ptsAndFlags: Long
        if (config) {
            ptsAndFlags = PACKET_FLAG_CONFIG // non-media data packet
        } else {
            ptsAndFlags = pts
            if (keyFrame) {
                ptsAndFlags = ptsAndFlags or PACKET_FLAG_KEY_FRAME
            }
        }

        headerBuffer.putLong(ptsAndFlags)
        headerBuffer.putInt(packetSize)
        headerBuffer.flip()
        IO.writeFully(fd, headerBuffer)
    }

    companion object {
        private const val PACKET_FLAG_CONFIG = 1L shl 63
        private const val PACKET_FLAG_KEY_FRAME = 1L shl 62

        @Throws(IOException::class)
        private fun fixOpusConfigPacket(buffer: ByteBuffer) {
            // Here is an example of the config packet received for an OPUS stream:
            //
            // 00000000  41 4f 50 55 53 48 44 52  13 00 00 00 00 00 00 00  |AOPUSHDR........|
            // -------------- BELOW IS THE PART WE MUST PUT AS EXTRADATA  -------------------
            // 00000010  4f 70 75 73 48 65 61 64  01 01 38 01 80 bb 00 00  |OpusHead..8.....|
            // 00000020  00 00 00                                          |...             |
            // ------------------------------------------------------------------------------
            // 00000020           41 4f 50 55 53  44 4c 59 08 00 00 00 00  |   AOPUSDLY.....|
            // 00000030  00 00 00 a0 2e 63 00 00  00 00 00 41 4f 50 55 53  |.....c.....AOPUS|
            // 00000040  50 52 4c 08 00 00 00 00  00 00 00 00 b4 c4 04 00  |PRL.............|
            // 00000050  00 00 00                                          |...|
            //
            // Each "section" is prefixed by a 64-bit ID and a 64-bit length.
            //
            // <https://developer.android.com/reference/android/media/MediaCodec#CSD>

            if (buffer.remaining() < 16) {
                throw IOException("Not enough data in OPUS config packet")
            }

            val opusHeaderId = byteArrayOf(
                'A'.code.toByte(),
                'O'.code.toByte(),
                'P'.code.toByte(),
                'U'.code.toByte(),
                'S'.code.toByte(),
                'H'.code.toByte(),
                'D'.code.toByte(),
                'R'.code.toByte()
            )
            val idBuffer = ByteArray(8)
            buffer[idBuffer]
            if (!idBuffer.contentEquals(opusHeaderId)) {
                throw IOException("OPUS header not found")
            }

            // The size is in native byte-order
            val sizeLong = buffer.getLong()
            if (sizeLong < 0 || sizeLong >= 0x7FFFFFFF) {
                throw IOException("Invalid block size in OPUS header: $sizeLong")
            }

            val size = sizeLong.toInt()
            if (buffer.remaining() < size) {
                throw IOException("Not enough data in OPUS header (invalid size: $size)")
            }

            // Set the buffer to point to the OPUS header slice
            buffer.limit(buffer.position() + size)
        }

        @Throws(IOException::class)
        private fun fixFlacConfigPacket(buffer: ByteBuffer) {
            // 00000000  66 4c 61 43 00 00 00 22                           |fLaC..."        |
            // -------------- BELOW IS THE PART WE MUST PUT AS EXTRADATA  -------------------
            // 00000000                           10 00 10 00 00 00 00 00  |        ........|
            // 00000010  00 00 0b b8 02 f0 00 00  00 00 00 00 00 00 00 00  |................|
            // 00000020  00 00 00 00 00 00 00 00  00 00                    |..........      |
            // ------------------------------------------------------------------------------
            // 00000020                                 84 00 00 28 20 00  |          ...( .|
            // 00000030  00 00 72 65 66 65 72 65  6e 63 65 20 6c 69 62 46  |..reference libF|
            // 00000040  4c 41 43 20 31 2e 33 2e  32 20 32 30 32 32 31 30  |LAC 1.3.2 202210|
            // 00000050  32 32 00 00 00 00                                 |22....|
            //
            // <https://developer.android.com/reference/android/media/MediaCodec#CSD>

            if (buffer.remaining() < 8) {
                throw IOException("Not enough data in FLAC config packet")
            }

            val flacHeaderId = byteArrayOf(
                'f'.code.toByte(),
                'L'.code.toByte(),
                'a'.code.toByte(),
                'C'.code.toByte()
            )
            val idBuffer = ByteArray(4)
            buffer[idBuffer]
            if (!idBuffer.contentEquals(flacHeaderId)) {
                throw IOException("FLAC header not found")
            }

            // The size is in big-endian
            buffer.order(ByteOrder.BIG_ENDIAN)

            val size = buffer.getInt()
            if (buffer.remaining() < size) {
                throw IOException("Not enough data in FLAC header (invalid size: $size)")
            }

            // Set the buffer to point to the FLAC header slice
            buffer.limit(buffer.position() + size)
        }
    }
}