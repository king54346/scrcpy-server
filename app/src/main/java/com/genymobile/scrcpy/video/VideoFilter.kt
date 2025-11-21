package com.genymobile.scrcpy.video

import android.graphics.Rect
import com.genymobile.scrcpy.device.Orientation
import com.genymobile.scrcpy.device.Size
import com.genymobile.scrcpy.util.AffineMatrix

/**
 * 视频滤镜 - 管理视频流的几何变换
 *
 * 作用：构建一个变换链，将原始捕获的视频帧转换为最终输出的视频帧
 *
 * 变换链示例：
 * 原始画面 → 裁剪 → 旋转 → 翻转 → 缩放 → 最终输出
 *
 * 典型使用场景：
 * 1. 手机屏幕旋转时自动调整视频方向
 * 2. 裁剪掉屏幕的刘海或黑边
 * 3. 缩放视频到目标分辨率
 * 4. 处理前置摄像头的镜像翻转
 *
 * @property outputSize 当前变换链输出的视频尺寸（会随变换更新）
 */
class VideoFilter(var outputSize: Size?) {

    /**
     * 直接变换矩阵
     * 描述如何从输入图像变换到输出图像
     * 例如：旋转90度、裁剪、缩放等操作的累积结果
     */
    var transform: AffineMatrix? = null
        private set

    /**
     * 逆变换矩阵
     *
     * 用途：
     * 1. OpenGL 着色器需要逆矩阵来变换纹理坐标（而非图像本身）
     * 2. 将客户端的鼠标/触摸坐标映射回设备的原始坐标
     *
     * 示例：如果视频旋转了90度，点击屏幕右上角 (x=100, y=10)
     *      需要用逆矩阵算出设备上真正的位置（可能是左上角）
     */
    val inverseTransform: AffineMatrix?
        get() = transform?.invert()

    /**
     * 添加裁剪变换
     *
     * 作用：裁剪掉画面的某些部分（如刘海、状态栏等）
     *
     * @param crop 裁剪区域（相对于当前输出的坐标系）
     * @param transposed 如果输入已经旋转过（宽高互换），需要转置裁剪区域
     *
     * 示例：
     * 输入 1920x1080，裁剪掉顶部 100px
     * crop = Rect(0, 0, 1920, 980)
     * 输出变为 1920x980
     */
    fun addCrop(crop: Rect, transposed: Boolean) {
        val adjustedCrop = if (transposed) {
            // 如果输入已旋转（宽高互换），需要转置裁剪矩形
            transposeRect(crop)
        } else {
            crop
        }

        val inputWidth = outputSize!!.width.toDouble()
        val inputHeight = outputSize!!.height.toDouble()

        // 验证裁剪区域在有效范围内
        require(
            adjustedCrop.left >= 0 &&
                    adjustedCrop.top >= 0 &&
                    adjustedCrop.right <= inputWidth &&
                    adjustedCrop.bottom <= inputHeight
        ) {
            "Crop $adjustedCrop exceeds the input area ($outputSize)"
        }

        // 计算归一化坐标（OpenGL 使用 0.0-1.0 范围）
        val x = adjustedCrop.left / inputWidth
        val y = 1 - (adjustedCrop.bottom / inputHeight) // OpenGL 原点在左下角，需要翻转 Y
        val w = adjustedCrop.width() / inputWidth
        val h = adjustedCrop.height() / inputHeight

        // 累积变换：新变换 * 已有变换
        transform = AffineMatrix.reframe(x, y, w, h).multiply(transform)

        // 更新输出尺寸
        outputSize = Size(adjustedCrop.width(), adjustedCrop.height())
    }

    /**
     * 添加正交旋转（90度的整数倍）
     *
     * @param ccwRotation 逆时针旋转次数（0-3，每次90度）
     *                    0=0°, 1=90°, 2=180°, 3=270°
     *
     * 示例：
     * 手机从竖屏 → 横屏，需要旋转 90°（ccwRotation=1）
     * 输出尺寸从 1080x1920 变为 1920x1080
     */
    fun addRotation(ccwRotation: Int) {
        if (ccwRotation == 0) return

        transform = AffineMatrix.rotateOrtho(ccwRotation).multiply(transform)

        // 90° 和 270° 旋转会交换宽高
        if (ccwRotation % 2 != 0) {
            outputSize = outputSize?.rotate()
        }
    }

    /**
     * 添加捕获方向变换
     *
     * 用途：处理摄像头/传感器的原始方向
     *
     * @param captureOrientation 捕获设备的方向信息
     *                           包含翻转标志和旋转角度
     *
     * 示例：
     * 前置摄像头通常需要水平翻转（镜像效果）
     */
    fun addOrientation(captureOrientation: Orientation) {
        // 处理翻转（如前置摄像头的镜像）
        if (captureOrientation.isFlipped) {
            transform = AffineMatrix.hflip().multiply(transform)
        }

        // 反向旋转以抵消设备方向
        val ccwRotation = (4 - captureOrientation.rotation) % 4
        addRotation(ccwRotation)
    }

    /**
     * 添加显示方向变换（考虑锁屏旋转）
     *
     * @param displayRotation 显示器当前旋转角度（0-3）
     * @param locked 是否锁定屏幕方向
     *               true：视频方向固定，不随设备旋转
     *               false：视频随设备旋转
     * @param captureOrientation 捕获设备的方向
     *
     * 场景：
     * 用户锁定竖屏，但手机横着拿 → 视频需要反向旋转保持正立
     */
    fun addOrientation(displayRotation: Int, locked: Boolean, captureOrientation: Orientation) {
        if (locked) {
            // 锁屏时，需要反向旋转来抵消显示器的物理旋转
            val reverseDisplayRotation = (4 - displayRotation) % 4
            addRotation(reverseDisplayRotation)
        }
        addOrientation(captureOrientation)
    }

    /**
     * 添加任意角度旋转（非90度整数倍）
     *
     * @param cwAngle 顺时针旋转角度（度数）
     *
     * 用途：摄像头的微小倾斜校正、用户自定义旋转等
     *
     * 示例：
     * 设备传感器显示倾斜了 5.3°，添加 -5.3° 来校正
     */
    fun addAngle(cwAngle: Double) {
        if (cwAngle == 0.0) return

        val ccwAngle = -cwAngle // 转换为逆时针角度
        transform = outputSize?.let {
            AffineMatrix.rotate(ccwAngle)
                .withAspectRatio(it)  // 保持宽高比
                .fromCenter()          // 绕中心旋转
                .multiply(transform)
        }
    }

    /**
     * 添加缩放变换
     *
     * 作用：将视频缩放到目标分辨率
     *
     * @param targetSize 目标输出尺寸
     *
     * 示例：
     * 原始 1920x1080 → 缩放到 1280x720 以节省带宽
     *
     * 注意：实际缩放由 OpenGL 视口完成，这里只是标记变换链需要运行
     */
    fun addResize(targetSize: Size?) {
        if (outputSize == targetSize) return

        // 即使没有其他变换，缩放时也需要运行 OpenGL 滤镜
        // 所以确保 transform 不为 null
        if (transform == null) {
            transform = AffineMatrix.IDENTITY
        }

        outputSize = targetSize
    }

    companion object {
        /**
         * 转置矩形（交换 x 和 y 坐标）
         *
         * 用途：当视频已旋转 90° 或 270° 时，裁剪坐标需要转置
         *
         * 示例：
         * 输入 Rect(10, 20, 100, 200)
         * 输出 Rect(20, 10, 200, 100)
         */
        private fun transposeRect(rect: Rect): Rect {
            return Rect(rect.top, rect.left, rect.bottom, rect.right)
        }
    }
}