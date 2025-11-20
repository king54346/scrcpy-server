package com.genymobile.scrcpy.video

import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.view.Surface
import androidx.annotation.RequiresApi
import com.genymobile.scrcpy.AndroidVersions
import com.genymobile.scrcpy.Options
import com.genymobile.scrcpy.control.PositionMapper
import com.genymobile.scrcpy.device.Orientation
import com.genymobile.scrcpy.device.Size
import com.genymobile.scrcpy.opengl.AffineOpenGLFilter
import com.genymobile.scrcpy.opengl.OpenGLRunner
import com.genymobile.scrcpy.util.AffineMatrix
import com.genymobile.scrcpy.util.Ln
import com.genymobile.scrcpy.wrappers.ServiceManager.displayManager
import com.genymobile.scrcpy.wrappers.ServiceManager.windowManager
import java.io.IOException

/**
 * 虚拟显示捕获器
 * 负责创建和管理虚拟显示器，处理视频流的捕获、转换和渲染
 */
class NewDisplayCapture(
    private val vdListener: VirtualDisplayListener?,
    options: Options
) : SurfaceCapture() {

    // === 配置参数 ===
    private val newDisplay = requireNotNull(options.newDisplay) {
        "newDisplay must not be null"
    }
    private var maxSize: Int = options.maxSize
    private val displayImePolicy: Int = options.displayImePolicy
    private val crop: Rect? = options.crop
    private val captureOrientationLocked: Boolean =
        options.captureOrientationLock != Orientation.Lock.Unlocked
    private val captureOrientation: Orientation = requireNotNull(options.captureOrientation) {
        "captureOrientation must not be null"
    }
    private val angle: Float = options.angle
    private val vdDestroyContent: Boolean = options.vDDestroyContent
    private val vdSystemDecorations: Boolean = options.vDSystemDecorations

    // === 显示相关状态 ===
    private var mainDisplaySize: Size? = null
    private var mainDisplayDpi = 0
    private var displaySize: Size? = null // 逻辑尺寸(包含旋转)
    private var physicalSize: Size? = null // 物理尺寸(不含旋转)
    private var dpi = 0

    @get:Synchronized
    override var size: Size? = null
        private set

    // === 渲染和变换 ===
    private var displayTransform: AffineMatrix? = null
    private var eventTransform: AffineMatrix? = null
    private var glRunner: OpenGLRunner? = null

    // === 虚拟显示器 ===
    private var virtualDisplay: VirtualDisplay? = null
    private val displaySizeMonitor: DisplaySizeMonitor = DisplaySizeMonitor()

    override fun init() {
        initializeDisplayParameters()
    }

    // 显示器准备阶段
    override fun prepare() {
        val displayRotation = prepareDisplaySize()
        val filter = createVideoFilter(displayRotation)
        calculateTransforms(displayRotation, filter)
    }

    // 显示开启
    @RequiresApi(Build.VERSION_CODES.Q)
    @Throws(IOException::class)
    override fun start(surface: Surface?) {
        //准备 OpenGL 运行器
        val finalSurface = prepareGLRunnerIfNeeded(surface)

        if (virtualDisplay == null) {
            // 创建新的虚拟显示器
            startNew(finalSurface)
        } else {
            // 当stop的时候会保留虚拟显示器
            virtualDisplay!!.surface = finalSurface
        }
        // 通知创建完成
        notifyVirtualDisplayCreated()
    }
    // 实现关闭
    override fun stop() {
        glRunner?.stopAndRelease()
        glRunner = null
    }
    //
    override fun release() {
        displaySizeMonitor.stopAndRelease()
        virtualDisplay?.release()
        virtualDisplay = null
    }

    @Synchronized
    override fun setMaxSize(newMaxSize: Int): Boolean {
        maxSize = newMaxSize
        return true
    }

    override fun requestInvalidate() {
        invalidate()
    }

    // === 私有辅助方法 ===

    /**
     * 初始化显示参数
     */
    private fun initializeDisplayParameters() {
        displaySize = newDisplay.size
        dpi = newDisplay.dpi

        // 如果新显示器没有指定尺寸或DPI,从主显示器获取
        if (displaySize == null || dpi == 0) {
            loadMainDisplayInfo()
        }
    }

    /**
     * 加载主显示器信息
     */
    private fun loadMainDisplayInfo() {
        val displayInfo = displayManager?.getDisplayInfo(0)

        if (displayInfo != null) {
            mainDisplaySize = displayInfo.size.let { size ->
                // 使用自然方向(rotation 0),而非当前方向
                if (displayInfo.rotation % 2 != 0) size.rotate() else size
            }
            mainDisplayDpi = displayInfo.dpi
        } else {
            Ln.w("Main display not found, fallback to 1920x1080 240dpi")
            mainDisplaySize = DEFAULT_DISPLAY_SIZE
            mainDisplayDpi = DEFAULT_DISPLAY_DPI
        }
    }

    /**
     * 准备显示尺寸
     * @return 显示旋转角度
     */
    private fun prepareDisplaySize(): Int {
        return if (virtualDisplay == null) {
            prepareNewDisplaySize()
        } else {
            prepareExistingDisplaySize()
        }
    }

    /**
     * 准备新虚拟显示器的尺寸
     */
    private fun prepareNewDisplaySize(): Int {
        if (!newDisplay.hasExplicitSize()) {
            displaySize = mainDisplaySize
        }
        if (!newDisplay.hasExplicitDpi()) {
            dpi = scaleDpi(
                requireNotNull(mainDisplaySize),
                mainDisplayDpi,
                requireNotNull(displaySize)
            )
        }

        size = displaySize
        displaySizeMonitor.sessionDisplaySize = displaySize
        return 0
    }

    /**
     * 准备已存在虚拟显示器的尺寸
     */
    private fun prepareExistingDisplaySize(): Int {
        val displayInfo = requireNotNull(
            displayManager?.getDisplayInfo(virtualDisplay!!.display.displayId)
        ) { "Failed to get display info" }

        displaySize = displayInfo.size
        dpi = displayInfo.dpi
        return displayInfo.rotation
    }

    /**
     * 创建视频滤镜
     */
    private fun createVideoFilter(displayRotation: Int): VideoFilter {
        val filter = VideoFilter(displaySize)

        // 添加裁剪
        crop?.let {
            val transposed = displayRotation % 2 != 0
            filter.addCrop(it, transposed)
        }

        // 添加方向和角度
        filter.addOrientation(displayRotation, captureOrientationLocked, captureOrientation)
        filter.addAngle(angle.toDouble())

        // 处理尺寸限制
        filter.outputSize?.let { filteredSize ->
            applyResizeIfNeeded(filter, filteredSize)
        }

        return filter
    }

    /**
     * 如果需要,应用尺寸调整
     */
    private fun applyResizeIfNeeded(filter: VideoFilter, filteredSize: Size) {
        val needsResize = !filteredSize.isMultipleOf8 ||
                (maxSize != 0 && filteredSize.max > maxSize)

        if (needsResize) {
            var newSize = filteredSize
            if (maxSize != 0) {
                newSize = newSize.limit(maxSize)
            }
            newSize = newSize.round8()
            filter.addResize(newSize)
        }
    }

    /**
     * 计算变换矩阵
     */
    private fun calculateTransforms(displayRotation: Int, filter: VideoFilter) {
        eventTransform = filter.inverseTransform
        size = filter.outputSize

        // 虚拟显示器视频始终保持原始方向,需要手动旋转
        physicalSize = if (displayRotation % 2 == 0) {
            displaySize
        } else {
            displaySize?.rotate()
        }

        // 创建显示旋转矩阵
        val displayFilter = VideoFilter(physicalSize)
        displayFilter.addRotation(displayRotation)
        val displayRotationMatrix = displayFilter.inverseTransform

        // 计算最终显示变换: displayRotationMatrix * eventTransform
        displayTransform = AffineMatrix.multiplyAll(displayRotationMatrix, eventTransform)
    }

    /**
     * 如果需要,准备 OpenGL 运行器
     */
    private fun prepareGLRunnerIfNeeded(surface: Surface?): Surface? {
        //  创建OpenGL渲染器来处理图形变换
        return if (displayTransform != null) {
            // 1. 检查GL运行器是否已存在（防止重复创建）
            check(glRunner == null) { "GL runner already exists" }
            // 2. 创建仿射变换的OpenGL过滤器
            val glFilter = AffineOpenGLFilter(displayTransform!!)
            glRunner = OpenGLRunner(glFilter)
            // 3. 验证必要的参数不为空
            requireNotNull(physicalSize)
            requireNotNull(size)
            requireNotNull(surface)
            // 4. 启动GL运行器，返回GL运行器内部的Surface
            glRunner!!.start(physicalSize!!, size!!, surface)
        } else {
            surface
        }
    }

    /**
     * 启动新的虚拟显示器
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun startNew(surface: Surface?) {
        try {
            val flags = buildVirtualDisplayFlags()

            virtualDisplay = displayManager?.createNewVirtualDisplay(
                VIRTUAL_DISPLAY_NAME,
                requireNotNull(displaySize).width,
                requireNotNull(displaySize).height,
                dpi,
                surface,
                flags
            )

            val virtualDisplayId = requireNotNull(virtualDisplay).display.displayId
            Ln.i("New display: ${displaySize!!.width}x${displaySize!!.height}/$dpi (id=$virtualDisplayId)")
            //  配置显示器输入法策略
            configureDisplayImePolicy(virtualDisplayId)
            // 监听显示器的变化
            // 通过DisplayListener 或者 使用 DisplayWindowListener 去注册监听方法
            displaySizeMonitor.start(virtualDisplayId) { invalidate() }

        } catch (e: Exception) {
            Ln.e("Could not create display", e)
            throw AssertionError("Could not create display", e)
        }
    }

    /**
     * 构建虚拟显示器标志位
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun buildVirtualDisplayFlags(): Int {
        var flags = (VIRTUAL_DISPLAY_FLAG_PUBLIC
                or VIRTUAL_DISPLAY_FLAG_PRESENTATION
                or VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                or VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH
                or VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT)

        if (vdDestroyContent) {
            flags = flags or VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL
        }
        if (vdSystemDecorations) {
            flags = flags or VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS
        }

        if (Build.VERSION.SDK_INT >= AndroidVersions.API_33_ANDROID_13) {
            flags = flags or (VIRTUAL_DISPLAY_FLAG_TRUSTED
                    or VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP
                    or VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED
                    or VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED)

            if (Build.VERSION.SDK_INT >= AndroidVersions.API_34_ANDROID_14) {
                flags = flags or (VIRTUAL_DISPLAY_FLAG_OWN_FOCUS
                        or VIRTUAL_DISPLAY_FLAG_DEVICE_DISPLAY_GROUP)
            }
        }

        return flags
    }

    /**
     * 配置显示器输入法策略
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun configureDisplayImePolicy(virtualDisplayId: Int) {
        if (displayImePolicy != -1) {
            windowManager?.setDisplayImePolicy(virtualDisplayId, displayImePolicy)
        }
    }

    /**
     * 通知虚拟显示器创建完成
     */
    private fun notifyVirtualDisplayCreated() {
        vdListener?.let { listener ->
            val positionMapper = size?.let {
                PositionMapper.create(it, eventTransform, displaySize)
            }
            listener.onNewVirtualDisplay(
                requireNotNull(virtualDisplay).display.displayId,
                positionMapper
            )
        }
    }

    companion object {
        private const val VIRTUAL_DISPLAY_NAME = "scrcpy"
        private val DEFAULT_DISPLAY_SIZE = Size(1920, 1080)
        private const val DEFAULT_DISPLAY_DPI = 240

        // Virtual display flags
        private const val VIRTUAL_DISPLAY_FLAG_PUBLIC = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
        private const val VIRTUAL_DISPLAY_FLAG_PRESENTATION = DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
        private const val VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
        private const val VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH = 1 shl 6
        private const val VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT = 1 shl 7
        private const val VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
        private const val VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS = 1 shl 9
        private const val VIRTUAL_DISPLAY_FLAG_TRUSTED = 1 shl 10
        private const val VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP = 1 shl 11
        private const val VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED = 1 shl 12
        private const val VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED = 1 shl 13
        private const val VIRTUAL_DISPLAY_FLAG_OWN_FOCUS = 1 shl 14
        private const val VIRTUAL_DISPLAY_FLAG_DEVICE_DISPLAY_GROUP = 1 shl 15

        /**
         * 根据尺寸缩放 DPI
         */
        private fun scaleDpi(initialSize: Size, initialDpi: Int, targetSize: Size): Int {
            return (initialDpi * targetSize.max) / initialSize.max
        }
    }
}