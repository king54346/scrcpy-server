package com.genymobile.scrcpy.util

import android.media.MediaCodec

interface Codec {
    enum class Type {
        VIDEO,
        AUDIO,
    }

    val type: Type?

    val id: Int

    val codecName: String?

    val mimeType: String?

    companion object {
        fun getMimeType(codec: MediaCodec): String? {
            val types = codec.codecInfo.supportedTypes
            return if (types.isNotEmpty()) types[0] else null
        }
    }
}