package com.genymobile.scrcpy.video

import android.graphics.Rect
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.IBinder
import android.view.Surface
import com.genymobile.scrcpy.AndroidVersions
import com.genymobile.scrcpy.Options
import com.genymobile.scrcpy.control.PositionMapper
import com.genymobile.scrcpy.device.ConfigurationException
import com.genymobile.scrcpy.device.Device
import com.genymobile.scrcpy.device.DisplayInfo
import com.genymobile.scrcpy.device.Orientation
import com.genymobile.scrcpy.device.Size
import com.genymobile.scrcpy.opengl.AffineOpenGLFilter
import com.genymobile.scrcpy.opengl.OpenGLRunner
import com.genymobile.scrcpy.util.AffineMatrix
import com.genymobile.scrcpy.util.Ln
import com.genymobile.scrcpy.util.LogUtils
import com.genymobile.scrcpy.wrappers.ServiceManager.displayManager
import com.genymobile.scrcpy.wrappers.SurfaceControl
import java.io.IOException

/**
 * 屏幕捕获实现类
 * 负责创建虚拟显示器并将屏幕内容渲染到指定 Surface
 */
class ScreenCapture(
    private val vdListener: VirtualDisplayListener?,
    options: Options
) : SurfaceCapture() {

    // 配置参数
    private val displayId = options.displayId
    private var maxSize: Int = options.maxSize
    private val crop: Rect? = options.crop
    private var captureOrientationLock: Orientation.Lock = options.captureOrientationLock
    private var captureOrientation: Orientation = options.captureOrientation
    private val angle: Float = options.angle

    // 显示器信息
    private var displayInfo: DisplayInfo? = null
    override var size: Size? = null
        private set

    // 监听器和渲染组件
    private val displaySizeMonitor = DisplaySizeMonitor()
    private var transform: AffineMatrix? = null
    private var glRunner: OpenGLRunner? = null

    // 虚拟显示器资源
    private var display: IBinder? = null
    private var virtualDisplay: VirtualDisplay? = null

    init {
        require(displayId != Device.DISPLAY_ID_NONE) {
            "Display ID cannot be DISPLAY_ID_NONE"
        }
    }

    override fun init() {
        displaySizeMonitor.start(displayId) { invalidate() }
    }

    @Throws(ConfigurationException::class)
    override fun prepare() {
        // 获取并验证显示器信息
        displayInfo = displayManager?.getDisplayInfo(displayId)
            ?: throw ConfigurationException(buildDisplayErrorMessage())

        checkProtectedBuffersSupport()

        val displaySize = displayInfo!!.size
        displaySizeMonitor.sessionDisplaySize = displaySize

        // 锁定初始方向
        if (captureOrientationLock == Orientation.Lock.LockedInitial) {
            captureOrientationLock = Orientation.Lock.LockedValue
            captureOrientation = Orientation.fromRotation(displayInfo!!.rotation)
        }

        // 构建视频滤镜链
        val (finalTransform, outputSize) = buildVideoFilter(displaySize)
        transform = finalTransform
        size = outputSize
    }

    @Throws(IOException::class)
    override fun start(surface: Surface?) {
        // 清理旧资源
        cleanupDisplayResources()

        val inputSize: Size
        val outputSurface: Surface?

        if (transform != null) {
            // 需要 OpenGL 滤镜处理
            inputSize = displayInfo!!.size
            outputSurface = startOpenGLPipeline(surface, inputSize)
        } else {
            // 直接渲染,无需滤镜
            inputSize = size ?: throw IllegalStateException("Size not initialized")
            outputSurface = surface
        }

        // 尝试创建虚拟显示器
        createVirtualDisplayWithFallback(inputSize, outputSurface)

        // 通知监听器
        notifyVirtualDisplayCreated(inputSize)
    }

    override fun stop() {
        glRunner?.stopAndRelease()
        glRunner = null
    }

    override fun release() {
        displaySizeMonitor.stopAndRelease()
        cleanupDisplayResources()
    }

    override fun setMaxSize(newMaxSize: Int): Boolean {
        maxSize = newMaxSize
        return true
    }

    override fun requestInvalidate() {
        invalidate()
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 清理显示器相关资源
     */
    private fun cleanupDisplayResources() {
        display?.let {
            SurfaceControl.destroyDisplay(it)
            display = null
        }
        virtualDisplay?.release()
        virtualDisplay = null
    }

    /**
     * 检查保护缓冲区支持
     */
    private fun checkProtectedBuffersSupport() {
        val hasProtectedBuffers = displayInfo?.flags?.let {
            (it and DisplayInfo.FLAG_SUPPORTS_PROTECTED_BUFFERS) != 0
        } ?: false

        if (!hasProtectedBuffers) {
            Ln.w("Display doesn't support protected buffers, DRM content mirroring may be restricted")
        }
    }

    /**
     * 构建显示器错误信息
     */
    private fun buildDisplayErrorMessage(): String {
        return "Display $displayId not found\n${LogUtils.buildDisplayListMessage()}"
            .also { Ln.e(it) }
    }

    /**
     * 构建视频滤镜链并返回变换矩阵和输出尺寸
     */
    private fun buildVideoFilter(displaySize: Size): Pair<AffineMatrix?, Size?> {
        val filter = VideoFilter(displaySize)

        // 应用裁剪
        crop?.let {
            val transposed = (displayInfo!!.rotation % 2) != 0
            filter.addCrop(it, transposed)
        }

        // 应用方向锁定
        val locked = captureOrientationLock != Orientation.Lock.Unlocked
        filter.addOrientation(displayInfo!!.rotation, locked, captureOrientation)

        // 应用旋转角度
        filter.addAngle(angle.toDouble())

        // 计算最终变换和输出尺寸
        val outputSize = filter.outputSize?.limit(maxSize)?.round8()
        return Pair(filter.inverseTransform, outputSize)
    }

    /**
     * 启动 OpenGL 处理管道
     */
    private fun startOpenGLPipeline(surface: Surface?, inputSize: Size): Surface? {
        require(glRunner == null) { "OpenGL runner already exists" }

        val glFilter = AffineOpenGLFilter(transform!!)
        glRunner = OpenGLRunner(glFilter)

        return size?.let { outputSize ->
            surface?.let { glRunner!!.start(inputSize, outputSize, it) }
        }
    }

    /**
     * 创建虚拟显示器,失败时使用 SurfaceControl 备用方案
     */
    private fun createVirtualDisplayWithFallback(inputSize: Size, surface: Surface?) {
        try {
            createVirtualDisplayUsingDisplayManager(inputSize, surface)
        } catch (dmException: Exception) {
            Ln.w("DisplayManager API failed, falling back to SurfaceControl")
            try {
                createVirtualDisplayUsingSurfaceControl(inputSize, surface)
            } catch (scException: Exception) {
                Ln.e("DisplayManager API failed", dmException)
                Ln.e("SurfaceControl API failed", scException)
                throw IOException("Failed to create virtual display with both methods")
            }
        }
    }

    /**
     * 使用 DisplayManager API 创建虚拟显示器 (推荐方式)
     */
    private fun createVirtualDisplayUsingDisplayManager(inputSize: Size, surface: Surface?) {
        virtualDisplay = displayManager?.createVirtualDisplay(
            "scrcpy",
            inputSize.width,
            inputSize.height,
            displayId,
            surface
        ) ?: throw IllegalStateException("DisplayManager not available")

        Ln.d("Virtual display created using DisplayManager API")
    }

    /**
     * 使用 SurfaceControl API 创建虚拟显示器 (备用方式)
     */
    private fun createVirtualDisplayUsingSurfaceControl(inputSize: Size, surface: Surface?) {
        display = createSecureDisplay()

        val deviceSize = displayInfo!!.size
        val layerStack = displayInfo!!.layerStack

        configureSurfaceControlDisplay(
            display!!,
            surface,
            deviceSize.toRect(),
            inputSize.toRect(),
            layerStack
        )

        Ln.d("Virtual display created using SurfaceControl API")
    }

    /**
     * 通知监听器虚拟显示器已创建
     */
    private fun notifyVirtualDisplayCreated(inputSize: Size) {
        vdListener ?: return

        val (virtualDisplayId, positionMapper) = when {
            virtualDisplay == null || displayId == 0 -> {
                // SurfaceControl 或主显示器
                val deviceSize = displayInfo!!.size
                Pair(displayId, size?.let { PositionMapper.create(it, transform, deviceSize) })
            }
            else -> {
                // 虚拟显示器
                Pair(
                    virtualDisplay!!.display.displayId,
                    size?.let { PositionMapper.create(it, transform, inputSize) }
                )
            }
        }

        vdListener.onNewVirtualDisplay(virtualDisplayId, positionMapper)
    }

    // ==================== 伴生对象 ====================

    companion object {
        /**
         * 创建安全显示器
         * Android 11 及以下允许安全显示,Android 12+ 禁止 shell 进程创建安全显示
         */
        @Throws(Exception::class)
        private fun createSecureDisplay(): IBinder {
            val secure = Build.VERSION.SDK_INT < AndroidVersions.API_30_ANDROID_11 ||
                    (Build.VERSION.SDK_INT == AndroidVersions.API_30_ANDROID_11 &&
                            Build.VERSION.CODENAME != "S")
            return SurfaceControl.createDisplay("scrcpy", secure)
        }

        /**
         * 配置 SurfaceControl 显示器参数
         */
        private fun configureSurfaceControlDisplay(
            display: IBinder,
            surface: Surface?,
            deviceRect: Rect,
            displayRect: Rect,
            layerStack: Int
        ) {
            SurfaceControl.openTransaction()
            try {
                SurfaceControl.setDisplaySurface(display, surface)
                SurfaceControl.setDisplayProjection(display, 0, deviceRect, displayRect)
                SurfaceControl.setDisplayLayerStack(display, layerStack)
            } finally {
                SurfaceControl.closeTransaction()
            }
        }
    }
}