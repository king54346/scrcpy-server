package com.genymobile.scrcpy.video

import android.annotation.SuppressLint
import android.hardware.camera2.CameraCharacteristics

enum class CameraFacing(val facingName: String, val value: Int) {
    FRONT("front", CameraCharacteristics.LENS_FACING_FRONT),
    BACK("back", CameraCharacteristics.LENS_FACING_BACK),

    @SuppressLint("InlinedApi")  // introduced in API 23
    EXTERNAL("external", CameraCharacteristics.LENS_FACING_EXTERNAL);

    companion object {
        fun findByName(name: String): CameraFacing? {
            return entries.find { it.facingName == name }
        }
    }
}