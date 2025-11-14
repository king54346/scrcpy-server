package com.genymobile.scrcpy.video

import android.annotation.SuppressLint
import android.media.MediaFormat
import com.genymobile.scrcpy.util.Codec

enum class VideoCodec(// 4-byte ASCII representation of the name
    override val id: Int, override val codecName: String, override val mimeType: String
) : Codec {
    H264(0x68323634, "h264", MediaFormat.MIMETYPE_VIDEO_AVC),
    H265(0x68323635, "h265", MediaFormat.MIMETYPE_VIDEO_HEVC),

    @SuppressLint("InlinedApi")  // introduced in API 29
    AV1(0x00617631, "av1", MediaFormat.MIMETYPE_VIDEO_AV1);

    override val type: Codec.Type
        get() = Codec.Type.VIDEO

    companion object {
        fun findByName(name: String): VideoCodec? {
            for (codec in entries) {
                if (codec.name == name) {
                    return codec
                }
            }
            return null
        }
    }
}