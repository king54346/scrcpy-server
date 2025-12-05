package com.genymobile.scrcpy.control

import com.genymobile.scrcpy.device.Point
import com.genymobile.scrcpy.device.Position
import com.genymobile.scrcpy.device.Size
import com.genymobile.scrcpy.util.AffineMatrix

class PositionMapper(val videoSize: Size, private val videoToDeviceMatrix: AffineMatrix?) {
    fun map(position: Position): Point? {
        val clientVideoSize = position.screenSize
        // 客户端屏幕尺寸和服务端当前视频尺寸不相等则返回null的point，只有当尺寸匹配的时候才会返回有效的point
        // 原因：
        // 用户在竖屏点击，但事件到达时已切换到横屏
        // 事件延迟到达时，屏幕尺寸已经改变
        // 等等
        if (videoSize != clientVideoSize) {
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
            val convertToPixels = videoSize != targetSize || filterTransform != null
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