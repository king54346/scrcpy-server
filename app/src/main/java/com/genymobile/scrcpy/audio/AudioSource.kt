package com.genymobile.scrcpy.audio

import android.annotation.SuppressLint
import android.media.MediaRecorder

@SuppressLint("InlinedApi")
enum class AudioSource(
    val sourceName: String,
    val directAudioSource: Int
) {
    OUTPUT("output", MediaRecorder.AudioSource.REMOTE_SUBMIX),
    MIC("mic", MediaRecorder.AudioSource.MIC),
    PLAYBACK("playback", -1),
    MIC_UNPROCESSED("mic-unprocessed", MediaRecorder.AudioSource.UNPROCESSED),
    MIC_CAMCORDER("mic-camcorder", MediaRecorder.AudioSource.CAMCORDER),
    MIC_VOICE_RECOGNITION("mic-voice-recognition", MediaRecorder.AudioSource.VOICE_RECOGNITION),
    MIC_VOICE_COMMUNICATION("mic-voice-communication", MediaRecorder.AudioSource.VOICE_COMMUNICATION),
    VOICE_CALL("voice-call", MediaRecorder.AudioSource.VOICE_CALL),
    VOICE_CALL_UPLINK("voice-call-uplink", MediaRecorder.AudioSource.VOICE_UPLINK),
    VOICE_CALL_DOWNLINK("voice-call-downlink", MediaRecorder.AudioSource.VOICE_DOWNLINK),
    VOICE_PERFORMANCE("voice-performance", MediaRecorder.AudioSource.VOICE_PERFORMANCE);

    val isDirect: Boolean
        get() = this != PLAYBACK

    companion object {
        // 使用惰性初始化的 Map 提高查找性能
        private val nameMap: Map<String, AudioSource> by lazy {
            entries.associateBy { it.sourceName }
        }

        /**
         * 根据源名称查找对应的 AudioSource
         * @param sourceName 音频源名称
         * @return 匹配的 AudioSource，如果未找到返回 null
         */
        fun findByName(sourceName: String): AudioSource? = nameMap[sourceName]
    }
}