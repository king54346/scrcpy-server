package com.genymobile.scrcpy.video

import android.annotation.SuppressLint
import android.graphics.Rect
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaCodec
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.view.Surface
import androidx.annotation.RequiresApi
import com.genymobile.scrcpy.AndroidVersions
import com.genymobile.scrcpy.Options
import com.genymobile.scrcpy.device.ConfigurationException
import com.genymobile.scrcpy.device.Orientation
import com.genymobile.scrcpy.device.Size
import com.genymobile.scrcpy.opengl.AffineOpenGLFilter
import com.genymobile.scrcpy.opengl.OpenGLRunner
import com.genymobile.scrcpy.util.AffineMatrix
import com.genymobile.scrcpy.util.Ln
import com.genymobile.scrcpy.util.LogUtils
import com.genymobile.scrcpy.wrappers.ServiceManager.cameraManager
import kotlinx.coroutines.*
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.math.abs

/**
 * 相机捕获实现 (Flow 版本)
 * 使用 Camera2 API + Kotlin Coroutines + Flow 捕获相机内容并编码为视频流
 * 支持高速录制、仿射变换、裁剪、事件流监听等功能
 */
class CameraCapture(options: Options) : SurfaceCapture() {

    // ========== 配置参数 ==========
    private val explicitCameraId = options.cameraId
    private val cameraFacing: CameraFacing? = options.cameraFacing
    private val explicitSize = options.cameraSize
    private var maxSize: Int = options.maxSize
    private val aspectRatio: CameraAspectRatio? = options.cameraAspectRatio
    private val fps: Int = options.cameraFps
    private val highSpeed: Boolean = options.cameraHighSpeed
    private val crop: Rect? = options.crop
    private val captureOrientation: Orientation = requireNotNull(options.captureOrientation) {
        "captureOrientation must not be null"
    }
    private val angle: Float = options.angle

    // ========== 运行时状态 ==========
    private var cameraId: String? = null
    private var captureSize: Size? = null
    override var size: Size? = null  // 经过 OpenGL 变换后的最终尺寸
        private set

    // OpenGL 变换相关
    private var transform: AffineMatrix? = null
    private var glRunner: OpenGLRunner? = null

    // 协程相关
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val cameraThread = HandlerThread("camera").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val cameraDispatcher = cameraHandler.asCoroutineDispatcher()

    // 相机设备和会话
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null

    // 连接状态标志
    private val disconnected = AtomicBoolean(false)

    // 捕获事件流
    private var captureEventsFlow: Flow<CaptureEvent>? = null

    // ========== 生命周期方法 ==========

    /**
     * 初始化相机捕获
     * 创建协程调度器并打开相机设备
     */
    @RequiresApi(Build.VERSION_CODES.S)
    @Throws(ConfigurationException::class, IOException::class)
    override fun init() {
        runBlocking(cameraDispatcher) {
            try {
                // 选择并打开相机
                cameraId = selectCamera(explicitCameraId, cameraFacing)
                    ?: throw ConfigurationException("No matching camera found")

                Ln.i("Using camera '$cameraId'")
                cameraDevice = openCameraAsync(cameraId!!)
            } catch (e: CameraAccessException) {
                throw IOException("Failed to access camera", e)
            } catch (e: CancellationException) {
                throw IOException("Camera initialization cancelled", e)
            }
        }
    }

    /**
     * 准备捕获参数
     * 选择捕获分辨率并构建变换矩阵
     */
    @Throws(IOException::class)
    override fun prepare() {
        try {
            // 选择合适的捕获分辨率
            captureSize = selectSize(cameraId!!, explicitSize, maxSize, aspectRatio, highSpeed)
                ?: throw IOException("Could not select camera size")

            // 构建视频滤镜链(裁剪→旋转→角度调整)
            val filter = VideoFilter(captureSize).apply {
                crop?.let { addCrop(it, false) }
                if (captureOrientation != Orientation.Orient0) {
                    addOrientation(captureOrientation)
                }
                addAngle(angle.toDouble())
            }

            // 保存逆变换矩阵和输出尺寸
            transform = filter.inverseTransform
            size = filter.outputSize?.limit(maxSize)?.round8()
        } catch (e: CameraAccessException) {
            throw IOException("Failed to prepare camera", e)
        }
    }

    /**
     * 开始捕获
     * 创建 OpenGL 渲染器(如需变换)并启动捕获会话
     */
    @RequiresApi(Build.VERSION_CODES.S)
    @Throws(IOException::class)
    override fun start(surface: Surface?) {
        requireNotNull(surface) { "Surface must not be null" }

        var targetSurface = surface

        // 如果需要应用变换,创建 OpenGL 渲染管道
        if (transform != null) {
            check(glRunner == null) { "OpenGL runner already initialized" }

            val glFilter = AffineOpenGLFilter(transform!!)
            glRunner = OpenGLRunner(glFilter, VFLIP_MATRIX)

            targetSurface = glRunner!!.start(
                captureSize ?: throw IOException("Capture size not initialized"),
                size ?: throw IOException("Output size not initialized"),
                surface
            )
        }

        // 使用协程启动相机捕获并监听事件
        scope.launch(cameraDispatcher) {
            try {
                // 创建捕获会话并开始连续捕获
                captureSession = targetSurface?.let {
                    createCaptureSessionAsync(
                        cameraDevice ?: throw IOException("Camera device not initialized"),
                        it
                    )
                }

                val request = targetSurface?.let { createCaptureRequest(it) }

                // 启动捕获并收集事件流
                captureEventsFlow = request?.let { createCaptureFlow(captureSession!!, it) }
                captureEventsFlow?.collect { event ->
                    handleCaptureEvent(event)
                }
            } catch (e: CameraAccessException) {
                withContext(Dispatchers.Main) {
                    stop()
                }
                throw IOException("Failed to start camera capture", e)
            } catch (e: CancellationException) {
                Ln.d("Camera capture cancelled")
                throw e
            }
        }
    }

    /**
     * 停止捕获
     * 清理 OpenGL 资源和捕获会话
     */
    override fun stop() {
        // 取消所有协程任务
        scope.coroutineContext.cancelChildren()

        glRunner?.stopAndRelease()
        glRunner = null

        captureSession?.close()
        captureSession = null

        captureEventsFlow = null
    }

    /**
     * 释放所有资源
     * 关闭相机设备并清理协程作用域
     */
    override fun release() {
        scope.cancel()

        cameraDevice?.close()
        cameraDevice = null

        // 清理线程资源
        cameraDispatcher.cancel()
        cameraThread.quitSafely()
    }

    /**
     * 动态更新最大尺寸
     * 仅在未指定固定尺寸时生效
     */
    override fun setMaxSize(maxSize: Int): Boolean {
        if (explicitSize != null) {
            return false
        }
        this.maxSize = maxSize
        return true
    }

    override val isClosed: Boolean
        get() = disconnected.get()

    override fun requestInvalidate() {
        // 相机镜像暂无控制器,不支持重置请求
    }

    // ========== 相机操作私有方法(协程 + Flow 版本) ==========

    /**
     * 异步打开相机设备
     * 使用 suspendCoroutine 将回调转换为挂起函数
     */
    @SuppressLint("MissingPermission")
    @RequiresApi(AndroidVersions.API_31_ANDROID_12)
    @Throws(CameraAccessException::class)
    private suspend fun openCameraAsync(id: String): CameraDevice = suspendCoroutine { continuation ->
        val callback = object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                Ln.d("Camera opened successfully")
                continuation.resume(camera)
            }

            override fun onDisconnected(camera: CameraDevice) {
                Ln.w("Camera disconnected")
                disconnected.set(true)
                invalidate()
                continuation.resumeWithException(
                    CameraAccessException(CameraAccessException.CAMERA_DISCONNECTED, "Camera disconnected")
                )
            }

            override fun onError(camera: CameraDevice, error: Int) {
                val errorCode = when (error) {
                    ERROR_CAMERA_IN_USE -> CameraAccessException.CAMERA_IN_USE
                    ERROR_MAX_CAMERAS_IN_USE -> CameraAccessException.MAX_CAMERAS_IN_USE
                    ERROR_CAMERA_DISABLED -> CameraAccessException.CAMERA_DISABLED
                    ERROR_CAMERA_DEVICE, ERROR_CAMERA_SERVICE -> CameraAccessException.CAMERA_ERROR
                    else -> CameraAccessException.CAMERA_ERROR
                }
                continuation.resumeWithException(CameraAccessException(errorCode))
            }
        }

        cameraManager!!.openCamera(id, callback, null)
    }

    /**
     * 异步创建捕获会话
     * 支持普通和高速录制模式
     */
    @RequiresApi(AndroidVersions.API_31_ANDROID_12)
    @Throws(CameraAccessException::class)
    private suspend fun createCaptureSessionAsync(
        camera: CameraDevice,
        surface: Surface
    ): CameraCaptureSession = suspendCoroutine { continuation ->
        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                continuation.resume(session)
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                continuation.resumeWithException(
                    CameraAccessException(CameraAccessException.CAMERA_ERROR, "Session configuration failed")
                )
            }
        }

        val sessionType = if (highSpeed) {
            SessionConfiguration.SESSION_HIGH_SPEED
        } else {
            SessionConfiguration.SESSION_REGULAR
        }

        val sessionConfig = SessionConfiguration(
            sessionType,
            listOf(OutputConfiguration(surface)),
            { it.run() },  // 使用同步执行器
            callback
        )

        camera.createCaptureSession(sessionConfig)
    }

    /**
     * 创建捕获请求
     * 设置录制模板和帧率参数
     */
    @Throws(CameraAccessException::class)
    private fun createCaptureRequest(surface: Surface): CaptureRequest {
        val builder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(surface)

            // 设置固定帧率
            if (fps > 0) {
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps, fps))
            }
        }

        return builder.build()
    }

    /**
     * 创建捕获事件流
     * 使用 callbackFlow 将 Camera2 回调转换为 Flow
     */
    @RequiresApi(AndroidVersions.API_31_ANDROID_12)
    private fun createCaptureFlow(
        session: CameraCaptureSession,
        request: CaptureRequest
    ): Flow<CaptureEvent> = callbackFlow {
        val callback = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureStarted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                timestamp: Long,
                frameNumber: Long
            ) {
                // 发送帧开始事件
                trySend(CaptureEvent.FrameStarted(frameNumber, timestamp))
            }

            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                // 发送帧完成事件
                trySend(CaptureEvent.FrameCompleted(result.frameNumber, result))
            }

            override fun onCaptureFailed(
                session: CameraCaptureSession,
                request: CaptureRequest,
                failure: CaptureFailure
            ) {
                // 发送失败事件
                Ln.w("Camera capture failed: frame ${failure.frameNumber}")
                trySend(CaptureEvent.FrameFailed(failure.frameNumber, failure))
            }
        }

        // 启动重复捕获
        try {
            if (highSpeed) {
                val highSpeedSession = session as CameraConstrainedHighSpeedCaptureSession
                val requests = highSpeedSession.createHighSpeedRequestList(request)
                highSpeedSession.setRepeatingBurst(requests, callback, null)
            } else {
                session.setRepeatingRequest(request, callback, null)
            }
        } catch (e: CameraAccessException) {
            close(e)
        }

        // 等待流关闭
        awaitClose {
            try {
                session.stopRepeating()
            } catch (e: CameraAccessException) {
                Ln.e("Failed to stop repeating request: ${e.message}")
            }
        }
    }.flowOn(cameraDispatcher)

    /**
     * 处理捕获事件
     * 可以在这里添加帧率统计、性能监控等逻辑
     */
    private fun handleCaptureEvent(event: CaptureEvent) {
        when (event) {
            is CaptureEvent.FrameStarted -> {
                // 可以记录帧开始时间用于性能分析
            }
            is CaptureEvent.FrameCompleted -> {
                // 可以从 TotalCaptureResult 中提取元数据
            }
            is CaptureEvent.FrameFailed -> {
                // 失败帧已经被记录,这里可以添加额外的错误处理
            }
        }
    }

    // ========== 捕获事件定义 ==========

    /**
     * 捕获事件密封类
     * 表示相机捕获过程中的各种事件
     */
    private sealed class CaptureEvent {
        data class FrameStarted(val frameNumber: Long, val timestamp: Long) : CaptureEvent()
        data class FrameCompleted(val frameNumber: Long, val result: TotalCaptureResult) : CaptureEvent()
        data class FrameFailed(val frameNumber: Long, val failure: CaptureFailure) : CaptureEvent()
    }

    // ========== 静态工具方法 ==========

    companion object {
        /**
         * 垂直翻转变换矩阵(列主序)
         * 用于修正 Camera2 SurfaceTexture 的坐标系统
         */
        private val VFLIP_MATRIX = floatArrayOf(
            1f,  0f, 0f, 0f,  // 第1列: X轴不变
            0f, -1f, 0f, 0f,  // 第2列: Y轴翻转
            0f,  0f, 1f, 0f,  // 第3列: Z轴不变
            0f,  1f, 0f, 1f   // 第4列: Y平移1.0(翻转后重新映射到[0,1])
        )

        /**
         * 选择相机设备
         * 优先级: 明确指定ID > 指定朝向 > 第一个可用相机
         */
        @Throws(CameraAccessException::class, ConfigurationException::class)
        private fun selectCamera(explicitCameraId: String?, cameraFacing: CameraFacing?): String? {
            val manager = cameraManager ?: return null
            val cameraIds = manager.cameraIdList

            // 1. 如果指定了相机ID,验证其存在性
            if (explicitCameraId != null) {
                if (explicitCameraId !in cameraIds) {
                    Ln.e("""
                        Camera with id $explicitCameraId not found
                        ${LogUtils.buildCameraListMessage(false)}
                    """.trimIndent())
                    throw ConfigurationException("Camera id not found")
                }
                return explicitCameraId
            }

            // 2. 如果未指定朝向,返回第一个相机
            if (cameraFacing == null) {
                return cameraIds.firstOrNull()
            }

            // 3. 查找匹配朝向的相机
            for (cameraId in cameraIds) {
                val characteristics = manager.getCameraCharacteristics(cameraId)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (facing == cameraFacing.value) {
                    return cameraId
                }
            }

            return null
        }

        /**
         * 选择捕获分辨率
         * 综合考虑:最大尺寸限制、宽高比、是否高速录制
         */
        @Throws(CameraAccessException::class)
        private fun selectSize(
            cameraId: String,
            explicitSize: Size?,
            maxSize: Int,
            aspectRatio: CameraAspectRatio?,
            highSpeed: Boolean
        ): Size? {
            // 如果明确指定了尺寸,直接返回
            if (explicitSize != null) {
                return explicitSize
            }

            val manager = cameraManager ?: return null
            val characteristics = manager.getCameraCharacteristics(cameraId)
            val configs = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: return null

            // 获取支持的尺寸列表(高速录制有独立的尺寸列表)
            val sizes = if (highSpeed) {
                configs.highSpeedVideoSizes
            } else {
                configs.getOutputSizes(MediaCodec::class.java)
            } ?: return null

            // 过滤和排序候选尺寸
            val targetAspectRatio = resolveAspectRatio(aspectRatio, characteristics)

            return sizes.asSequence()
                .filter { size ->
                    // 过滤:不超过最大尺寸限制
                    maxSize <= 0 || (size.width <= maxSize && size.height <= maxSize)
                }
                .filter { size ->
                    // 过滤:匹配目标宽高比(±10%容差)
                    if (targetAspectRatio == null) {
                        true
                    } else {
                        val aspectRatioValue = size.width.toFloat() / size.height
                        val ratio = aspectRatioValue / targetAspectRatio
                        ratio in 0.9f..1.1f
                    }
                }
                .maxWithOrNull(compareBy<android.util.Size> { it.width }
                    .thenBy { size ->
                        // 次要排序:优先选择更接近目标宽高比的尺寸
                        if (targetAspectRatio == null) {
                            0f
                        } else {
                            val aspectRatioValue = size.width.toFloat() / size.height
                            val ratio = aspectRatioValue / targetAspectRatio
                            -abs(1 - ratio)  // 距离越小越好(负值使其降序)
                        }
                    }
                    .thenBy { it.height }
                )
                ?.let { Size(it.width, it.height) }
        }

        /**
         * 解析目标宽高比
         * 支持传感器原生宽高比或自定义数值
         */
        private fun resolveAspectRatio(
            ratio: CameraAspectRatio?,
            characteristics: CameraCharacteristics
        ): Float? {
            if (ratio == null) return null

            return if (ratio.isSensor) {
                // 使用传感器的原生宽高比
                val activeSize = characteristics.get(
                    CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE
                ) ?: return null
                activeSize.width().toFloat() / activeSize.height()
            } else {
                ratio.aspectRatio
            }
        }
    }
}