package com.genymobile.scrcpy.video

enum class VideoSource(val value: String) {
    DISPLAY("display"),
    CAMERA("camera");

    companion object {
        fun findByName(name: String): VideoSource? {
            return entries.find { it.value == name }
        }
    }
}