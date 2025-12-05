package com.genymobile.scrcpy.audio

import android.media.MediaCodec
import java.nio.ByteBuffer

interface AudioCapture {
    @Throws(AudioCaptureException::class)
    fun checkCompatibility()

    @Throws(AudioCaptureException::class)
    fun start()
    fun stop()

    /**
     * Read a chunk of [AudioConfig.MAX_READ_SIZE] samples.
     *
     * @param outDirectBuffer The target buffer
     * @param outBufferInfo The info to provide to MediaCodec
     * @return the number of bytes actually read.
     */
    fun read(outDirectBuffer: ByteBuffer, outBufferInfo: MediaCodec.BufferInfo): Int
}