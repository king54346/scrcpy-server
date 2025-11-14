package com.genymobile.scrcpy.audio

import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaCodec
import com.genymobile.scrcpy.util.Ln
import java.nio.ByteBuffer

/**
 * 音频数据读取器
 *
 * 职责:
 * 1. 从 AudioRecord 读取 PCM 音频数据
 * 2. 计算精确的 PTS (Presentation Time Stamp)
 * 3. 确保 PTS 单调递增以避免音视频同步问题
 *
 * PTS 来源优先级:
 * - 优先: 硬件时间戳 (AudioRecord.getTimestamp)
 * - 降级: 基于上一帧 PTS + 帧时长估算
 * - 兜底: 系统时间 (首帧时使用)
 */
class AudioRecordReader(private val recorder: AudioRecord) {
    // 硬件时间戳对象(可复用,避免频繁创建)
    private val timestamp = AudioTimestamp()

    // 上一次硬件时间戳(纳秒),用于检测时间戳是否更新
    private var lastHardwareTimestampNs: Long = UNINITIALIZED_TIMESTAMP

    // 上一帧的 PTS(微秒),用于单调性检查
    private var lastPtsUs: Long = 0

    // 下一帧预测的 PTS(微秒),用于时间戳估算
    private var nextPredictedPtsUs: Long = 0

    // 是否已警告过无法获取时间戳
    private var hasWarnedTimestampFailure = false

    /**
     * 读取音频数据到缓冲区
     *
     * @param outDirectBuffer 输出缓冲区(直接内存)
     * @param outBufferInfo 输出缓冲区元数据(偏移、大小、PTS、标志)
     * @return 读取的字节数,失败时返回负值
     */
    fun read(outDirectBuffer: ByteBuffer, outBufferInfo: MediaCodec.BufferInfo): Int {
        // 1. 读取音频数据
        val bytesRead = recorder.read(outDirectBuffer, AudioConfig.MAX_READ_SIZE)
        if (bytesRead <= 0) {
            // 读取失败或无数据可读
            return bytesRead
        }

        // 2. 获取时间戳(优先硬件,降级估算)
        val ptsUs = getTimestampUs(bytesRead)

        // 3. 计算帧时长并更新预测 PTS
        val frameDurationUs = calculateFrameDurationUs(bytesRead)
        nextPredictedPtsUs = ptsUs + frameDurationUs

        // 4. 确保 PTS 单调递增
        val monotonePtsUs = enforceMonotonicity(ptsUs)

        // 5. 更新 BufferInfo
        outBufferInfo.set(
            /* offset = */ 0,
            /* size = */ bytesRead,
            /* presentationTimeUs = */ monotonePtsUs,
            /* flags = */ 0
        )

        return bytesRead
    }

    /**
     * 获取当前帧的时间戳(微秒)
     *
     * 优先级:
     * 1. 硬件时间戳 (AudioRecord.getTimestamp)
     * 2. 预测时间戳 (基于上一帧 PTS + 时长)
     * 3. 系统时间 (首帧兜底)
     */
    private fun getTimestampUs(bytesRead: Int): Long {
        // 尝试获取硬件时间戳
        val isHardwareTimestampAvailable = recorder.getTimestamp(
            timestamp,
            AudioTimestamp.TIMEBASE_MONOTONIC
        ) == AudioRecord.SUCCESS

        // 检查硬件时间戳是否有效且已更新
        val isTimestampUpdated = isHardwareTimestampAvailable &&
                timestamp.nanoTime != lastHardwareTimestampNs

        return if (isTimestampUpdated) {
            // 方案1: 使用硬件时间戳(最精确)
            lastHardwareTimestampNs = timestamp.nanoTime
            timestamp.nanoTime / 1000  // 纳秒 -> 微秒
        } else {
            // 方案2/3: 使用预测时间戳或系统时间
            getEstimatedTimestampUs()
        }
    }

    /**
     * 获取估算的时间戳(当硬件时间戳不可用时)
     */
    private fun getEstimatedTimestampUs(): Long {
        if (nextPredictedPtsUs == 0L) {
            // 首帧: 使用系统时间作为起点
            if (!hasWarnedTimestampFailure) {
                Ln.w("Hardware audio timestamp unavailable, using system time fallback")
                hasWarnedTimestampFailure = true
            }
            nextPredictedPtsUs = System.nanoTime() / 1000
        }

        // 使用预测的 PTS
        return nextPredictedPtsUs
    }

    /**
     * 计算音频帧的时长(微秒)
     *
     * 公式: duration = bytes / (channels × bytes_per_sample × sample_rate)
     *
     * 例如: 48000 Hz, 16-bit, 立体声
     * - 1 秒数据 = 48000 × 2 × 2 = 192000 字节
     * - 4800 字节 = 4800 / 192000 = 0.025 秒 = 25000 微秒
     */
    private fun calculateFrameDurationUs(bytesRead: Int): Long {
        val bytesPerSecond = AudioConfig.CHANNELS *
                AudioConfig.BYTES_PER_SAMPLE *
                AudioConfig.SAMPLE_RATE

        return bytesRead * 1_000_000L / bytesPerSecond
    }

    /**
     * 强制 PTS 单调递增
     *
     * 为什么需要?
     * - 硬件时间戳可能倒退(时钟漂移、驱动 bug)
     * - 估算时间戳可能与硬件时间戳交替使用,产生跳变
     * - 非单调 PTS 会导致音视频播放器丢帧或卡顿
     *
     * 策略: 如果新 PTS < 上一帧 PTS + 1个样本时长,则强制递增
     */
    private fun enforceMonotonicity(ptsUs: Long): Long {
        val minAllowedPtsUs = if (lastPtsUs != 0L) {
            lastPtsUs + MIN_PTS_INCREMENT_US
        } else {
            ptsUs  // 首帧无需检查
        }

        val correctedPtsUs = maxOf(ptsUs, minAllowedPtsUs)

        if (correctedPtsUs != ptsUs) {
            Ln.d("PTS corrected for monotonicity: $ptsUs -> $correctedPtsUs us")
        }

        lastPtsUs = correctedPtsUs
        return correctedPtsUs
    }

    companion object {
        // 未初始化的时间戳标记
        private const val UNINITIALIZED_TIMESTAMP = -1L

        /**
         * 最小 PTS 增量(微秒)
         *
         * 等于 1 个音频样本的时长,例如:
         * - 48000 Hz: 1/48000 秒 ≈ 21 微秒
         * - 44100 Hz: 1/44100 秒 ≈ 23 微秒
         *
         * 计算方式: ceil(1_000_000 / sample_rate)
         */
        private val MIN_PTS_INCREMENT_US: Long = run {
            // 向上取整,确保至少 1 个样本的间隔
            val sampleDuration = (1_000_000.0 / AudioConfig.SAMPLE_RATE).toLong()
            maxOf(sampleDuration, 1)  // 至少 1 微秒
        }
    }
}