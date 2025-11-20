package com.genymobile.scrcpy.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import com.genymobile.scrcpy.AsyncProcessor
import com.genymobile.scrcpy.Options
import com.genymobile.scrcpy.device.ConfigurationException
import com.genymobile.scrcpy.device.Size
import com.genymobile.scrcpy.device.Streamer
import com.genymobile.scrcpy.util.Codec
import com.genymobile.scrcpy.util.CodecOption
import com.genymobile.scrcpy.util.CodecUtils
import com.genymobile.scrcpy.util.IO
import com.genymobile.scrcpy.util.Ln
import com.genymobile.scrcpy.util.LogUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * Surface 编码器 (Flow 版本)
 * 使用响应式流处理视频编码
 */
class SurfaceEncoder(
    private val capture: SurfaceCapture,
    private val streamer: Streamer,
    options: Options
) : AsyncProcessor {

    // 编码器配置
    private val encoderName = options.videoEncoder
    private val codecOptions = options.videoCodecOptions
    private val videoBitRate = options.videoBitRate
    private val maxFps = options.maxFps
    private val downsizeOnError = options.downsizeOnError

    // 状态跟踪
    private var firstFrameSent = false
    private var consecutiveErrors = 0
    private val stopped = AtomicBoolean(false)

    // 协程相关
    private var encodingJob: Job? = null
    private val coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Flow 状态管理
    private val resetFlow = MutableSharedFlow<Unit>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private val reset = CaptureReset()

    // ==================== 主流程 ====================

    override fun start(listener: AsyncProcessor.TerminationListener?) {
        encodingJob = coroutineScope.launch {
            try {
                streamCaptureLoop()
            } catch (e: CancellationException) {
                Ln.d("Video encoding cancelled")
                throw e
            } catch (e: ConfigurationException) {
                Ln.e("Configuration error: ${e.message}")
            } catch (e: IOException) {
                if (!IO.isBrokenPipe(e)) {
                    Ln.e("Video encoding error", e)
                }
            } finally {
                Ln.d("Screen streaming stopped")
                withContext(NonCancellable) {
                    listener?.onTerminated(true)
                }
            }
        }
    }

    override fun stop() {
        stopped.set(true)
        resetFlow.tryEmit(Unit)
        encodingJob?.cancel()
    }

    override fun join() {
        runBlocking {
            encodingJob?.join()
        }
    }

    // ==================== 编码主循环 (Flow 驱动) ====================

    private suspend fun streamCaptureLoop() {
        val codec = streamer.codec
        val mediaCodec = createMediaCodec(codec, encoderName)
        val format = createFormat(codec.mimeType!!, videoBitRate, maxFps, codecOptions)

        capture.init(reset)

        try {
            // 创建编码会话流
            encodingSessionFlow(mediaCodec, format)
                .onEach { result ->
                    handleSessionResult(result)
                }
                .catch { e ->
                    when {
                        e is CancellationException -> throw e
                        e is IOException && IO.isBrokenPipe(e) -> throw e
                        else -> Ln.e("Encoding session error", e)
                    }
                }
                .collect()

        } finally {
            mediaCodec.release()
            capture.release()
        }
    }

    /**
     * 编码会话流 - 生成编码会话序列
     */
    private fun encodingSessionFlow(
        mediaCodec: MediaCodec,
        format: MediaFormat
    ): Flow<SessionResult> = flow {
        var headerWritten = false

        while (!stopped.get()) {
            // 准备捕获会话
            val captureConfig = prepareCaptureSession(headerWritten)

            if (!captureConfig.headerWritten) {
                streamer.writeVideoHeader(captureConfig.size)
                headerWritten = true
            }

            // 配置格式
            configureMediaFormat(format, captureConfig.size)

            // 执行单次编码会话
            val result = runEncodingSession(mediaCodec, format, captureConfig.size)
            emit(result)

            // 根据结果决定是否继续
            when (result) {
                is SessionResult.Success -> {
                    if (capture.isClosed) {
                        Ln.d("Capture closed, stopping encoder")
                        return@flow
                    }
                }
                is SessionResult.ResetRequested -> {
                    Ln.d("Reset requested, restarting capture")
                }
                is SessionResult.Error -> {
                    // 错误处理会在 handleSessionResult 中进行
                    return@flow
                }
            }
        }
    }

    /**
     * 处理会话结果
     */
    private suspend fun handleSessionResult(result: SessionResult) {
        when (result) {
            is SessionResult.Error -> {
                if (!handleEncodingError(result.error, result.size)) {
                    throw result.error
                }
            }
            else -> { /* Success 和 ResetRequested 不需要处理 */ }
        }
    }

    // ==================== 会话管理 ====================

    private data class CaptureConfig(
        val size: Size,
        val headerWritten: Boolean
    )

    private sealed class SessionResult {
        object Success : SessionResult()
        object ResetRequested : SessionResult()
        data class Error(val error: Exception, val size: Size) : SessionResult()
    }

    private fun prepareCaptureSession(headerWritten: Boolean): CaptureConfig {
        reset.consumeReset()
        capture.prepare()
        val size = capture.size ?: throw IllegalStateException("Capture size not available")
        return CaptureConfig(size, headerWritten)
    }

    private fun configureMediaFormat(format: MediaFormat, size: Size) {
        format.setInteger(MediaFormat.KEY_WIDTH, size.width)
        format.setInteger(MediaFormat.KEY_HEIGHT, size.height)
    }

    private suspend fun runEncodingSession(
        mediaCodec: MediaCodec,
        format: MediaFormat,
        size: Size
    ): SessionResult {
        var surface: Surface? = null
        var mediaCodecStarted = false
        var captureStarted = false

        return try {
            // 配置并启动编码器
            mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = mediaCodec.createInputSurface()

            // 启动捕获
            capture.start(surface)
            captureStarted = true

            // 启动编码器
            mediaCodec.start()
            mediaCodecStarted = true

            // 注册重置监听
            reset.setRunningMediaCodec(mediaCodec)

            // 检查停止信号
            if (stopped.get()) {
                return SessionResult.Success
            }

            // 检查重置请求
            if (reset.consumeReset()) {
                return SessionResult.ResetRequested
            }

            // 使用 Flow 处理编码循环
            encodeFlow(mediaCodec)
                .catch { e ->
                    when {
                        e is IOException && IO.isBrokenPipe(e) -> throw e
                        e is CancellationException -> throw e
                        else -> throw e
                    }
                }
                .collect()

            SessionResult.Success

        } catch (e: Exception) {
            when {
                IO.isBrokenPipe(e) -> throw e
                e is CancellationException -> throw e
                else -> SessionResult.Error(e, size)
            }
        } finally {
            reset.setRunningMediaCodec(null)

            // 清理资源
            if (captureStarted) {
                capture.stop()
            }
            if (mediaCodecStarted) {
                try {
                    mediaCodec.stop()
                } catch (e: IllegalStateException) {
                    Ln.w("Error stopping MediaCodec", e)
                }
            }
            mediaCodec.reset()
            surface?.release()
        }
    }

    // ==================== 编码循环 (Flow 实现) ====================

    /**
     * 编码输出流 - 持续从 MediaCodec 读取编码数据
     */
    private fun encodeFlow(codec: MediaCodec): Flow<OutputBuffer> = flow {
        val bufferInfo = MediaCodec.BufferInfo()

        while (!stopped.get()) {
            // 使用 IO 调度器进行阻塞调用
            val outputBufferId = withContext(Dispatchers.IO) {
                codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_MS)
            }

            if (outputBufferId >= 0 && bufferInfo.size > 0) {
                val buffer = OutputBuffer(
                    bufferId = outputBufferId,
                    info = bufferInfo.copy(),
                    codec = codec
                )
                emit(buffer)
            } else if (outputBufferId >= 0) {
                // 空包,释放
                codec.releaseOutputBuffer(outputBufferId, false)
            }

            // 检查 EOS
            val eos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
            if (eos) {
                Ln.d("Received EOS from encoder")
                return@flow
            }

            // 避免阻塞
            yield()
        }
    }
        .flowOn(Dispatchers.IO) // 在 IO 调度器上执行
        .onEach { buffer ->
            processOutputBuffer(buffer)
        }
        .onCompletion {
            Ln.d("Encode flow completed")
        }

    /**
     * 输出缓冲区数据类
     */
    private data class OutputBuffer(
        val bufferId: Int,
        val info: MediaCodec.BufferInfo,
        val codec: MediaCodec
    )

    /**
     * 处理单个输出缓冲区
     */
    private suspend fun processOutputBuffer(buffer: OutputBuffer) {
        try {
            val codecBuffer = buffer.codec.getOutputBuffer(buffer.bufferId)
                ?: throw IllegalStateException("Output buffer ${buffer.bufferId} is null")

            val isConfig = (buffer.info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0

            if (!isConfig) {
                // 非配置包 = 实际视频帧
                firstFrameSent = true
                consecutiveErrors = 0
            }

            // 写入数据包
            withContext(Dispatchers.IO) {
                streamer.writePacket(codecBuffer, buffer.info)
            }

        } finally {
            buffer.codec.releaseOutputBuffer(buffer.bufferId, false)
        }
    }

    // ==================== 错误处理与重试 ====================

    private suspend fun handleEncodingError(error: Exception, currentSize: Size): Boolean {
        Ln.e("Encoding error: ${error.javaClass.simpleName}: ${error.message}")

        if (firstFrameSent) {
            return handleRuntimeError()
        }

        if (!downsizeOnError) {
            return false
        }

        return handleFirstFrameError(currentSize)
    }

    private suspend fun handleRuntimeError(): Boolean {
        consecutiveErrors++

        if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
            Ln.e("Too many consecutive errors ($consecutiveErrors), failing")
            return false
        }

        // 短暂延迟后重试
        delay(ERROR_RETRY_DELAY_MS)
        return true
    }

    private fun handleFirstFrameError(currentSize: Size): Boolean {
        val newMaxSize = chooseMaxSizeFallback(currentSize)

        if (newMaxSize == 0) {
            Ln.e("No smaller size available, failing")
            return false
        }

        if (!capture.setMaxSize(newMaxSize)) {
            Ln.e("Failed to set max size to $newMaxSize")
            return false
        }

        Ln.i("Retrying with -m$newMaxSize...")
        return true
    }

    // ==================== 工具函数 ====================

    /**
     * 复制 BufferInfo (因为它会被重用)
     */
    private fun MediaCodec.BufferInfo.copy(): MediaCodec.BufferInfo {
        return MediaCodec.BufferInfo().also {
            it.set(this.offset, this.size, this.presentationTimeUs, this.flags)
        }
    }

    // ==================== 伴生对象 ====================

    companion object {
        // MediaCodec 配置
        private const val DEFAULT_I_FRAME_INTERVAL = 10 // seconds
        private const val REPEAT_FRAME_DELAY_US = 100_000L // 100ms
        private const val KEY_MAX_FPS_TO_ENCODER = "max-fps-to-encoder"
        private const val DEQUEUE_TIMEOUT_MS = 10_000L // 10 seconds

        // 错误处理
        private const val MAX_CONSECUTIVE_ERRORS = 3
        private const val ERROR_RETRY_DELAY_MS = 50L
        private val MAX_SIZE_FALLBACK = intArrayOf(2560, 1920, 1600, 1280, 1024, 800)

        /**
         * 选择降级分辨率
         */
        private fun chooseMaxSizeFallback(failedSize: Size): Int {
            val currentMaxSize = max(failedSize.width, failedSize.height)
            return MAX_SIZE_FALLBACK.firstOrNull { it < currentMaxSize } ?: 0
        }

        /**
         * 创建 MediaCodec 实例
         */
        @Throws(IOException::class, ConfigurationException::class)
        private fun createMediaCodec(codec: Codec, encoderName: String?): MediaCodec {
            if (encoderName != null) {
                return createEncoderByName(codec, encoderName)
            }
            return createDefaultEncoder(codec)
        }

        private fun createEncoderByName(codec: Codec, encoderName: String): MediaCodec {
            Ln.d("Creating encoder by name: '$encoderName'")

            try {
                val mediaCodec = MediaCodec.createByCodecName(encoderName)
                val mimeType = Codec.getMimeType(mediaCodec)

                if (codec.mimeType != mimeType) {
                    val error = "Encoder '$encoderName' type ($mimeType) does not match codec type (${codec.mimeType})"
                    Ln.e(error)
                    throw ConfigurationException("Incorrect encoder type: $encoderName")
                }

                return mediaCodec

            } catch (e: IllegalArgumentException) {
                val message = buildString {
                    appendLine("Video encoder '$encoderName' for ${codec.codecName} not found")
                    append(LogUtils.buildVideoEncoderListMessage())
                }
                Ln.e(message)
                throw ConfigurationException("Unknown encoder: $encoderName")
            } catch (e: IOException) {
                val message = buildString {
                    appendLine("Could not create video encoder '$encoderName' for ${codec.codecName}")
                    append(LogUtils.buildVideoEncoderListMessage())
                }
                Ln.e(message)
                throw e
            }
        }

        private fun createDefaultEncoder(codec: Codec): MediaCodec {
            try {
                val mediaCodec = MediaCodec.createEncoderByType(codec.mimeType!!)
                Ln.d("Using video encoder: '${mediaCodec.name}'")
                return mediaCodec

            } catch (e: Exception) {
                val message = buildString {
                    appendLine("Could not create default video encoder for ${codec.codecName}")
                    append(LogUtils.buildVideoEncoderListMessage())
                }
                Ln.e(message)
                throw when (e) {
                    is IOException -> e
                    else -> IOException("Failed to create encoder", e)
                }
            }
        }

        /**
         * 创建 MediaFormat 配置
         */
        private fun createFormat(
            videoMimeType: String,
            bitRate: Int,
            maxFps: Float,
            codecOptions: List<CodecOption>?
        ): MediaFormat {
            return MediaFormat().apply {
                // 基础配置
                setString(MediaFormat.KEY_MIME, videoMimeType)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, 60) // 必须设置,但实际帧率可变
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )

                // 颜色范围 (Android 7.0+)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)

                // I-Frame 间隔
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, DEFAULT_I_FRAME_INTERVAL)

                // 重复帧设置
                setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, REPEAT_FRAME_DELAY_US)

                // 最大帧率限制
                if (maxFps > 0) {
                    setFloat(KEY_MAX_FPS_TO_ENCODER, maxFps)
                }

                // 自定义编解码器选项
                codecOptions?.forEach { option ->
                    CodecUtils.setCodecOption(this, option.key, option.value)
                    Ln.d("Video codec option: ${option.key} = ${option.value} (${option.value.javaClass.simpleName})")
                }
            }
        }
    }
}