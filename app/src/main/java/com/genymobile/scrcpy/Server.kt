package com.genymobile.scrcpy

import android.annotation.SuppressLint
import android.os.Build
import android.os.Looper
import androidx.annotation.RequiresApi
import com.genymobile.scrcpy.audio.*
import com.genymobile.scrcpy.control.Controller
import com.genymobile.scrcpy.device.*
import com.genymobile.scrcpy.opengl.OpenGLRunner
import com.genymobile.scrcpy.util.Ln
import com.genymobile.scrcpy.util.Ln.e
import com.genymobile.scrcpy.util.Ln.i
import com.genymobile.scrcpy.util.LogUtils
import com.genymobile.scrcpy.video.*
import kotlinx.coroutines.*
import java.io.File
import java.io.IOException
import kotlin.system.exitProcess

/**
 * scrcpy 服务端主类
 * 负责在 Android 设备上捕获音视频并通过网络传输
 */
object Server {
    val SERVER_PATH: String = getServerPath()

    private fun getServerPath(): String {
        return System.getProperty("java.class.path")
            ?.split(File.pathSeparator)
            ?.firstOrNull()
            ?: throw IllegalStateException("Cannot determine server path")
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    @JvmStatic
    fun main(args: Array<String>) {
        val status = runBlocking {
            try {
                internalMain(args)
                0
            } catch (t: Throwable) {
                Ln.e("Fatal error: ${t.message}", t)
                1
            }
        }
        exitProcess(status)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    @Throws(Exception::class)
    private suspend fun internalMain(args: Array<String>) {
        setupUncaughtExceptionHandler()
        prepareMainLooper()

        val options = Options.parse(args)
        initializeLogging(options)
        logDeviceInfo()

        // 处理列表查询请求（不启动镜像）
        if (options.list) {
            handleListRequests(options)
            return
        }

        // 启动镜像服务
        try {
            scrcpy(options)
        } catch (e: ConfigurationException) {
            // 用户友好的错误已记录，不打印堆栈
            Ln.e("Configuration error: ${e.message}")
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    @Throws(IOException::class, ConfigurationException::class)
    private suspend fun scrcpy(options: Options) {
        validateOptions(options)

        val cleanUp = if (options.cleanup) CleanUp.start(options) else null
        Workarounds.apply()

        val connection = DesktopConnection.open(
            options.scid,
            options.isTunnelForward,
            options.video,
            options.audio,
            options.control,
            options.sendDummyByte
        )

        try {
            runMirroringSession(connection, options, cleanUp)
        } finally {
            cleanupResources(connection, cleanUp)
        }
    }

    private fun validateOptions(options: Options) {
        when {
            Build.VERSION.SDK_INT < AndroidVersions.API_31_ANDROID_12
                    && options.videoSource == VideoSource.CAMERA -> {
                throw ConfigurationException("Camera mirroring requires Android 12+")
            }

            Build.VERSION.SDK_INT < AndroidVersions.API_29_ANDROID_10 -> {
                if (options.newDisplay != null) {
                    throw ConfigurationException("New virtual display requires Android 10+")
                }
                if (options.displayImePolicy != -1) {
                    throw ConfigurationException("Display IME policy requires Android 10+")
                }
            }
        }
    }

    /**
     * 运行镜像会话
     * 协调所有异步处理器的生命周期
     */
    private suspend fun runMirroringSession(
        connection: DesktopConnection,
        options: Options,
        cleanUp: CleanUp?
    ) = coroutineScope {
        // 发送设备元数据
        if (options.sendDeviceMeta) {
            connection.sendDeviceMeta(Device.deviceName)
        }

        // 创建处理器列表
        val asyncProcessors = mutableListOf<AsyncProcessor>()

        // 设置控制器
        val controller = setupController(connection, options, cleanUp, asyncProcessors)

        // 设置音频管道
        if (options.audio) {
            setupAudioPipeline(connection, options, asyncProcessors)
        }

        // 设置视频管道
        if (options.video) {
            setupVideoPipeline(connection, options, controller, asyncProcessors)
        }

        // 启动所有处理器并等待完成
        startAndAwaitProcessors(asyncProcessors)
    }

    private fun setupController(
        connection: DesktopConnection,
        options: Options,
        cleanUp: CleanUp?,
        asyncProcessors: MutableList<AsyncProcessor>
    ): Controller? {
        if (!options.control) return null

        val controlChannel = connection.controlChannel
            ?: throw IllegalStateException("Control enabled but no control channel")

        return Controller(controlChannel, cleanUp, options).also {
            asyncProcessors.add(it)
        }
    }

    private fun setupAudioPipeline(
        connection: DesktopConnection,
        options: Options,
        asyncProcessors: MutableList<AsyncProcessor>
    ) {
        // 创建音频采集器
        val audioCapture = if (options.audioSource.isDirect) {
            AudioDirectCapture(options.audioSource)
        } else {
            AudioPlaybackCapture(options.audioDup)
        }

        // 创建音频流传输器
        val audioStreamer = connection.audioFd?.let {
            Streamer(it, options.audioCodec, options.sendCodecMeta, options.sendFrameMeta)
        } ?: throw IllegalStateException("Audio enabled but no audio stream")

        // 创建音频录制/编码器
        val audioRecorder = when (options.audioCodec) {
            AudioCodec.RAW -> AudioRawRecorder(audioCapture, audioStreamer)
            else -> AudioEncoder(audioCapture, audioStreamer, options)
        }

        asyncProcessors.add(audioRecorder)
    }

    private fun setupVideoPipeline(
        connection: DesktopConnection,
        options: Options,
        controller: Controller?,
        asyncProcessors: MutableList<AsyncProcessor>
    ) {
        val videoStreamer = connection.videoFd?.let {
            Streamer(it, options.videoCodec, options.sendCodecMeta, options.sendFrameMeta)
        } ?: throw IllegalStateException("Video enabled but no video stream")

        // 创建屏幕捕获对象
        val surfaceCapture = createSurfaceCapture(options, controller)

        // 创建 Surface 编码器
        val surfaceEncoder = SurfaceEncoder(surfaceCapture, videoStreamer, options)

        asyncProcessors.add(surfaceEncoder)
        controller?.setSurfaceCapture(surfaceCapture)
    }

    private fun createSurfaceCapture(
        options: Options,
        controller: Controller?
    ): SurfaceCapture {
        return when (options.videoSource) {
            VideoSource.DISPLAY -> {
                options.newDisplay?.let {
                    // 创建新的虚拟显示器并捕获
                    NewDisplayCapture(controller, options)
                } ?: run {
                    check(options.displayId != Device.DISPLAY_ID_NONE) {
                        "Display ID must be specified"
                    }
                    // 捕获现有显示器
                    i("捕获现有显示器${options.displayId}")
                    ScreenCapture(controller, options)
                }
            }
            // 摄像头捕获
            else -> CameraCapture(options)
        }
    }

    /**
     * 启动所有异步处理器并等待它们完成
     * 使用协程替代 Looper.loop() 阻塞主线程
     */
    private suspend fun startAndAwaitProcessors(asyncProcessors: List<AsyncProcessor>) = coroutineScope {
        val completionDeferred = CompletableDeferred<Boolean>()
        val completion = ProcessorCompletion(asyncProcessors.size, completionDeferred)

        // 启动所有处理器
        asyncProcessors.forEach { processor ->
            processor.start { fatalError ->
                completion.addCompleted(fatalError)
            }
        }

        // 并行运行 Looper 和等待完成
        val looperJob = launch(Dispatchers.Main) {
            Looper.loop()
        }

        try {
            // 等待所有处理器完成
            val hadFatalError = completionDeferred.await()

            if (hadFatalError) {
                Ln.e("Fatal error occurred in one or more processors")
            }
        } finally {
            // 确保 Looper 被停止
            Looper.getMainLooper().quitSafely()
            looperJob.cancel()
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun cleanupResources(
        connection: DesktopConnection,
        cleanUp: CleanUp?
    ) = coroutineScope {
        // 并行清理所有资源
        val jobs = listOfNotNull(
            cleanUp?.let {
                launch {
                    it.interrupt()
                    it.join()
                }
            },
            launch {
                OpenGLRunner.quit()
                OpenGLRunner.join()
            },
            launch {
                connection.shutdown()
                connection.close()
            }
        )

        // 等待所有清理完成
        jobs.forEach { it.join() }
    }

    private fun setupUncaughtExceptionHandler() {
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Ln.e("Uncaught exception on thread ${thread.name}", throwable)
        }
    }

    private fun prepareMainLooper() {
        Looper.prepare()
        synchronized(Looper::class.java) {
            try {
                @SuppressLint("DiscouragedPrivateApi")
                val field = Looper::class.java.getDeclaredField("sMainLooper")
                field.isAccessible = true
                field[null] = Looper.myLooper()
            } catch (e: ReflectiveOperationException) {
                throw AssertionError("Failed to set main looper", e)
            }
        }
    }

    private fun initializeLogging(options: Options) {
        Ln.disableSystemStreams()
        Ln.initLogLevel(options.logLevel)
    }

    private fun logDeviceInfo() {
        val deviceInfo = buildString {
            append("Device: [${Build.MANUFACTURER}] ")
            append("${Build.BRAND} ${Build.MODEL} ")
            append("(Android ${Build.VERSION.RELEASE})")
        }
        Ln.i(deviceInfo)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun handleListRequests(options: Options) {
        if (options.cleanup) {
            CleanUp.unlinkSelf()
        }

        if (options.listEncoders) {
            Ln.i(LogUtils.buildVideoEncoderListMessage())
            Ln.i(LogUtils.buildAudioEncoderListMessage())
        }
        if (options.listDisplays) {
            Ln.i(LogUtils.buildDisplayListMessage())
        }
        if (options.listCameras || options.listCameraSizes) {
            Workarounds.apply()
            Ln.i(LogUtils.buildCameraListMessage(options.listCameraSizes))
        }
        if (options.listApps) {
            Workarounds.apply()
            Ln.i("Processing Android apps... (this may take some time)")
            Ln.i(LogUtils.buildAppListMessage())
        }
    }

    /**
     * 跟踪异步处理器完成状态（协程版本）
     * 使用 CompletableDeferred 替代 synchronized + Looper.quit
     */
    private class ProcessorCompletion(
        private var running: Int,
        private val completionDeferred: CompletableDeferred<Boolean>
    ) {
        private var fatalError = false

        @Synchronized
        fun addCompleted(fatalError: Boolean) {
            this.running--
            if (fatalError) {
                this.fatalError = true
            }

            // 全部完成或遇到致命错误时通知
            if (running == 0 || this.fatalError) {
                completionDeferred.complete(this.fatalError)
            }
        }
    }
}