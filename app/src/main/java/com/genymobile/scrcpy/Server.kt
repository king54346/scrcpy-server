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
import com.genymobile.scrcpy.util.LogUtils
import com.genymobile.scrcpy.video.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
        val classPaths = System.getProperty("java.class.path")
            ?.split(File.pathSeparator)
            ?.firstOrNull()
        return classPaths ?: throw IllegalStateException("Cannot determine server path")
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    @JvmStatic
    fun main(args: Array<String>) {
        var status = 0
        val job = CoroutineScope(Dispatchers.Default).launch {
            try {
                internalMain(args)
            } catch (t: Throwable) {
                Ln.e("Fatal error: ${t.message}", t)
                status = 1
            }
        }

        // 阻塞等待作业完成
        runBlocking {
            job.join()
        }

        exitProcess(status)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    @Throws(Exception::class)
    private fun internalMain(args: Array<String>) {
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
        }
    }

    //    1. 创建连接和处理器
    //    2.  启动所有异步任务
    //    3. Looper.loop() 等待完成
    @RequiresApi(Build.VERSION_CODES.Q)
    @Throws(IOException::class, ConfigurationException::class)
    private fun scrcpy(options: Options) {
        validateOptions(options)
        // cleanup monitor 进程
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
            //  启动所有异步任务
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

    private fun runMirroringSession(
        connection: DesktopConnection,
        options: Options,
        cleanUp: CleanUp?
    ) {
        //  发送设备元数据
        if (options.sendDeviceMeta) {
            connection.sendDeviceMeta(Device.deviceName)
        }
        //   创建处理器列表
        val asyncProcessors = mutableListOf<AsyncProcessor>()
        //  设置控制器：监听客户端的触摸、键盘、鼠标输入 将输入转换为 Android 事件注入设备
        val controller = setupController(connection, options, cleanUp, asyncProcessors)
        //  设置音频管道
        if (options.audio) {
            setupAudioPipeline(connection, options, asyncProcessors)
        }
        // 设置视频管道
        if (options.video) {
            setupVideoPipeline(connection, options, controller, asyncProcessors)
        }
        //  启动所有处理器
        startAsyncProcessors(asyncProcessors)
        Looper.loop() // 主线程等待，直到被 Completion 中断
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
        //  创建音频采集器
        val audioCapture = if (options.audioSource.isDirect) {
            //            如果是直接音频源 → 使用 AudioDirectCapture
            AudioDirectCapture(options.audioSource)
        } else {
            //  否则 → 使用 AudioPlaybackCapture（音频重定向/复制）
            AudioPlaybackCapture(options.audioDup)
        }
            //  创建音频流传输器 (audioStreamer
        val audioStreamer = connection.audioFd?.let {
            Streamer(it, options.audioCodec, options.sendCodecMeta, options.sendFrameMeta)
        } ?: throw IllegalStateException("Audio enabled but no audio stream")
            //    创建音频录制/编码器 (audioRecorder
        val audioRecorder = when (options.audioCodec) {
            //  如果编码格式是 RAW（原始音频）→  AudioRawRecorder 直接录制
            AudioCodec.RAW -> AudioRawRecorder(audioCapture, audioStreamer)
            //  其他格式（如 AAC、Opus）→ 使用 AudioEncoder 进行编码
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
        //创建 Surface 编码器
        val surfaceEncoder = SurfaceEncoder(surfaceCapture, videoStreamer, options)

        asyncProcessors.add(surfaceEncoder)
        controller?.setSurfaceCapture(surfaceCapture)
    }

    private fun createSurfaceCapture(
        options: Options,
        controller: Controller?
    ): SurfaceCapture {
        // 2 种视频源： 显示器源和摄像头源
        return when (options.videoSource) {
            // 分2中情况: 创建新显示器  捕获现有显示器
            VideoSource.DISPLAY -> {
                options.newDisplay?.let {
                    //  创建一个新的虚拟显示器并捕获
                    NewDisplayCapture(controller, options)
                } ?: run {
                    check(options.displayId != Device.DISPLAY_ID_NONE) {
                        "Display ID must be specified"
                    }
                    // 捕获现有显示器, 通过 options中的displayId
                    ScreenCapture(controller, options)
                }
            }
            //   对于非显示器的视频源(比如摄像头) 使用 CameraCapture 进行捕获
            else -> CameraCapture(options)
        }
    }

    private fun startAsyncProcessors(asyncProcessors: List<AsyncProcessor>) {
        val completion = Completion(asyncProcessors.size)
        asyncProcessors.forEach { processor ->
            processor.start { fatalError ->
                completion.addCompleted(fatalError)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun cleanupResources(
        connection: DesktopConnection,
        cleanUp: CleanUp?
    ) {
        cleanUp?.interrupt()
        // todo  停止所有的  asyncProcessors
        OpenGLRunner.quit()
        connection.shutdown()

        try {
            cleanUp?.join()
            OpenGLRunner.join()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        connection.close()
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
     * 跟踪异步处理器完成状态
     * 当所有处理器完成或发生致命错误时退出主循环
     */
    private class Completion(private var running: Int) {
        private var fatalError = false

        @Synchronized
        fun addCompleted(fatalError: Boolean) {
            this.running--
            if (fatalError) {
                this.fatalError = true
            }

            // 全部完成或遇到致命错误时退出
            if (running == 0 || this.fatalError) {
                Looper.getMainLooper().quitSafely()
            }
        }
    }
}