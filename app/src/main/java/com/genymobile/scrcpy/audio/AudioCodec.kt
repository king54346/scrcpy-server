package com.genymobile.scrcpy.audio

import android.media.MediaFormat
import com.genymobile.scrcpy.util.Codec

enum class AudioCodec(// 4-byte ASCII representation of the name
    override val id: Int, override val codecName: String, override val mimeType: String
) : Codec {
    OPUS(0x6f707573, "opus", MediaFormat.MIMETYPE_AUDIO_OPUS),
    AAC(0x00616163, "aac", MediaFormat.MIMETYPE_AUDIO_AAC),
    FLAC(0x666c6163, "flac", MediaFormat.MIMETYPE_AUDIO_FLAC),
    RAW(0x00726177, "raw", MediaFormat.MIMETYPE_AUDIO_RAW);

    override val type: Codec.Type
        get() = Codec.Type.AUDIO

    companion object {
        fun findByName(name: String): AudioCodec? {
            for (codec in entries) {
                if (codec.name == name) {
                    return codec
                }
            }
            return null
        }
    }
}