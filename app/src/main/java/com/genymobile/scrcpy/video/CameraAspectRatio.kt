package com.genymobile.scrcpy.video

class CameraAspectRatio private constructor(val aspectRatio: Float) {
    val isSensor: Boolean
        get() = aspectRatio == SENSOR

    companion object {
        private const val SENSOR = -1f

        fun fromFloat(ar: Float): CameraAspectRatio {
            require(!(ar < 0)) { "Invalid aspect ratio: $ar" }
            return CameraAspectRatio(ar)
        }

        fun fromFraction(w: Int, h: Int): CameraAspectRatio {
            require(!(w <= 0 || h <= 0)) { "Invalid aspect ratio: $w:$h" }
            return CameraAspectRatio(w.toFloat() / h)
        }

        fun sensorAspectRatio(): CameraAspectRatio {
            return CameraAspectRatio(SENSOR)
        }
    }
}