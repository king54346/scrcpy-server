package com.genymobile.scrcpy.control

import com.genymobile.scrcpy.device.Point
import com.genymobile.scrcpy.device.Position
import com.genymobile.scrcpy.device.Size
import com.genymobile.scrcpy.util.AffineMatrix

class PositionMapper(val videoSize: Size, private val videoToDeviceMatrix: AffineMatrix?) {
    fun map(position: Position): Point? {
        val clientVideoSize = position.screenSize
        if (!videoSize.equals(clientVideoSize)) {
            // The client sends a click relative to a video with wrong dimensions,
            // the device may have been rotated since the event was generated, so ignore the event
            return null
        }

        var point = position.point
        if (videoToDeviceMatrix != null) {
            point = videoToDeviceMatrix.apply(point)
        }
        return point
    }

    companion object {
        fun create(
            videoSize: Size,
            filterTransform: AffineMatrix?,
            targetSize: Size?
        ): PositionMapper {
            val convertToPixels = !videoSize.equals(targetSize) || filterTransform != null
            var transform = filterTransform
            if (convertToPixels) {
                val inputTransform = AffineMatrix.ndcFromPixels(videoSize)
                val outputTransform = AffineMatrix.ndcToPixels(targetSize!!)
                transform = outputTransform.multiply(transform).multiply(inputTransform)
            }

            return PositionMapper(videoSize, transform)
        }
    }
}