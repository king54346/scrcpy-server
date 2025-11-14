package com.genymobile.scrcpy.audio

import android.annotation.TargetApi
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaCodec
import com.genymobile.scrcpy.AndroidVersions
import com.genymobile.scrcpy.util.Ln.w
import java.nio.ByteBuffer

class AudioRecordReader(private val recorder: AudioRecord) {
    private val timestamp = AudioTimestamp()
    private var previousRecorderTimestamp: Long = -1
    private var previousPts: Long = 0
    private var nextPts: Long = 0

    fun read(outDirectBuffer: ByteBuffer, outBufferInfo: MediaCodec.BufferInfo): Int {
        val r = recorder.read(outDirectBuffer, AudioConfig.MAX_READ_SIZE)
        if (r <= 0) {
            return r
        }

        var pts: Long

        val ret = recorder.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_MONOTONIC)
        if (ret == AudioRecord.SUCCESS && timestamp.nanoTime != previousRecorderTimestamp) {
            pts = timestamp.nanoTime / 1000
            previousRecorderTimestamp = timestamp.nanoTime
        } else {
            if (nextPts == 0L) {
                w("Could not get initial audio timestamp")
                nextPts = System.nanoTime() / 1000
            }
            // compute from previous timestamp and packet size
            pts = nextPts
        }

        val durationUs =
            r * 1000000L / (AudioConfig.CHANNELS * AudioConfig.BYTES_PER_SAMPLE * AudioConfig.SAMPLE_RATE)
        nextPts = pts + durationUs

        if (previousPts != 0L && pts < previousPts + ONE_SAMPLE_US) {
            // Audio PTS may come from two sources:
            //  - recorder.getTimestamp() if the call works;
            //  - an estimation from the previous PTS and the packet size as a fallback.
            //
            // Therefore, the property that PTS are monotonically increasing is no guaranteed in corner cases, so enforce it.
            pts = previousPts + ONE_SAMPLE_US
        }
        previousPts = pts

        outBufferInfo[0, r, pts] = 0
        return r
    }

    companion object {
        private const val ONE_SAMPLE_US =
            ((1000000 + AudioConfig.SAMPLE_RATE - 1) / AudioConfig.SAMPLE_RATE // 1 sample in microseconds (used for fixing PTS)
                    ).toLong()
    }
}