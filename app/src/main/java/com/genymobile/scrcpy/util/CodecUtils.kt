package com.genymobile.scrcpy.util

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import java.util.Arrays

object CodecUtils {
    fun setCodecOption(format: MediaFormat, key: String, value: Any?) {
        if (value is Int) {
            format.setInteger(key, value)
        } else if (value is Long) {
            format.setLong(key, value)
        } else if (value is Float) {
            format.setFloat(key, value)
        } else if (value is String) {
            format.setString(key, value)
        }
    }

    fun getEncoders(codecs: MediaCodecList, mimeType: String): Array<MediaCodecInfo> {
        val result: MutableList<MediaCodecInfo> = ArrayList()
        for (codecInfo in codecs.codecInfos) {
            if (codecInfo.isEncoder && Arrays.asList<String>(*codecInfo.supportedTypes)
                    .contains(mimeType)
            ) {
                result.add(codecInfo)
            }
        }
        return result.toTypedArray<MediaCodecInfo>()
    }
}