package com.genymobile.scrcpy.video

import android.graphics.Rect
import com.genymobile.scrcpy.device.Orientation
import com.genymobile.scrcpy.device.Size
import com.genymobile.scrcpy.util.AffineMatrix

class VideoFilter(var outputSize: Size?) {
    var transform: AffineMatrix? = null
        private set

    val inverseTransform: AffineMatrix?
        /**
         * Return the inverse transform.
         *
         *
         * The direct affine transform describes how the input image is transformed.
         *
         *
         * It is often useful to retrieve the inverse transform instead:
         *
         *  * The OpenGL filter expects the matrix to transform the image *coordinates*, which is the inverse transform;
         *  * The click positions must be transformed back to the device positions, using the inverse transform too.
         *
         *
         * @return the inverse transform
         */
        get() {
            if (transform == null) {
                return null
            }
            return transform!!.invert()
        }

    fun addCrop(crop: Rect, transposed: Boolean) {
        var crop = crop
        if (transposed) {
            crop = transposeRect(crop)
        }

        val inputWidth = outputSize!!.width.toDouble()
        val inputHeight = outputSize!!.height.toDouble()

        require(!(crop.left < 0 || crop.top < 0 || crop.right > inputWidth || crop.bottom > inputHeight)) { "Crop " + crop + " exceeds the input area (" + outputSize + ")" }

        val x = crop.left / inputWidth
        val y = 1 - (crop.bottom / inputHeight) // OpenGL origin is bottom-left
        val w = crop.width() / inputWidth
        val h = crop.height() / inputHeight

        transform = AffineMatrix.reframe(x, y, w, h).multiply(transform)
        outputSize = Size(crop.width(), crop.height())
    }

    fun addRotation(ccwRotation: Int) {
        if (ccwRotation == 0) {
            return
        }

        transform = AffineMatrix.rotateOrtho(ccwRotation).multiply(transform)
        if (ccwRotation % 2 != 0) {
            outputSize = outputSize!!.rotate()
        }
    }

    fun addOrientation(captureOrientation: Orientation) {
        if (captureOrientation.isFlipped) {
            transform = AffineMatrix.hflip().multiply(transform)
        }
        val ccwRotation = (4 - captureOrientation.rotation) % 4
        addRotation(ccwRotation)
    }

    fun addOrientation(displayRotation: Int, locked: Boolean, captureOrientation: Orientation) {
        if (locked) {
            // flip/rotate the current display from the natural device orientation (i.e. where display rotation is 0)
            val reverseDisplayRotation = (4 - displayRotation) % 4
            addRotation(reverseDisplayRotation)
        }
        addOrientation(captureOrientation)
    }

    fun addAngle(cwAngle: Double) {
        if (cwAngle == 0.0) {
            return
        }
        val ccwAngle = -cwAngle
        transform = outputSize?.let {
            AffineMatrix.rotate(ccwAngle).withAspectRatio(it).fromCenter()
                .multiply(transform)
        }
    }

    fun addResize(targetSize: Size?) {
        if (outputSize!!.equals(targetSize)) {
            return
        }

        if (transform == null) {
            // The requested scaling is performed by the viewport (by changing the output size), but the OpenGL filter must still run, even if
            // resizing is not performed by the shader. So transform MUST NOT be null.
            transform = AffineMatrix.IDENTITY
        }
        outputSize = targetSize
    }

    companion object {
        private fun transposeRect(rect: Rect): Rect {
            return Rect(rect.top, rect.left, rect.bottom, rect.right)
        }
    }
}