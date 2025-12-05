package com.genymobile.scrcpy.audio

import android.media.AudioFormat

object AudioConfig {
    const val SAMPLE_RATE: Int = 48000
    const val CHANNEL_CONFIG: Int = AudioFormat.CHANNEL_IN_STEREO
    const val CHANNELS: Int = 2
    const val CHANNEL_MASK: Int = AudioFormat.CHANNEL_IN_LEFT or AudioFormat.CHANNEL_IN_RIGHT
    const val ENCODING: Int = AudioFormat.ENCODING_PCM_16BIT
    const val BYTES_PER_SAMPLE: Int = 2

    // Never read more than 1024 samples, even if the buffer is bigger (that would increase latency).
    // A lower value is useless, since the system captures audio samples by blocks of 1024 (so for example if we read by blocks of 256 samples, we
    // receive 4 successive blocks without waiting, then we wait for the 4 next ones).
    const val MAX_READ_SIZE: Int = 1024 * CHANNELS * BYTES_PER_SAMPLE

    fun createAudioFormat(): AudioFormat {
        val builder = AudioFormat.Builder()
        builder.setEncoding(ENCODING)
        builder.setSampleRate(SAMPLE_RATE)
        builder.setChannelMask(CHANNEL_CONFIG)
        return builder.build()
    }
}