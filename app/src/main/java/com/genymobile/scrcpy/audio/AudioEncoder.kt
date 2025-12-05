package com.genymobile.scrcpy.audio

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import com.genymobile.scrcpy.AndroidVersions
import com.genymobile.scrcpy.AsyncProcessor
import com.genymobile.scrcpy.Options
import com.genymobile.scrcpy.device.ConfigurationException
import com.genymobile.scrcpy.device.Streamer
import com.genymobile.scrcpy.util.Codec
import com.genymobile.scrcpy.util.CodecOption
import com.genymobile.scrcpy.util.CodecUtils.setCodecOption
import com.genymobile.scrcpy.util.IO.isBrokenPipe
import com.genymobile.scrcpy.util.Ln
import com.genymobile.scrcpy.util.LogUtils.buildAudioEncoderListMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * 音频编码器 (统一 Flow 管道版本)
 *
 * 使用单一 Flow 管道处理所有 MediaCodec 事件:
 * ```
 * MediaCodec 事件 Flow (输入/输出/错误)
 *   ↓ filter: 分离输入事件
 *   ↓ map: 读取音频 + 送入编码器
 *
 * MediaCodec 事件 Flow
 *   ↓ filter: 分离输出事件
 *   ↓ map: 读取编码数据
 *   ↓ map: 修复 PTS
 *   ↓ collect: 发送到网络
 * ```
 *
 * @property capture 音频捕获器
 * @property streamer 网络流传输器
 * @property options 编码选项
 */
class AudioEncoder(
    private val capture: AudioCapture,
    private val streamer: Streamer,
    private val options: Options
) : AsyncProcessor {

    // 配置参数
    private val bitRate = options.audioBitRate
    private val codecOptions = options.audioCodecOptions
    private val encoderName = options.audioEncoder

    // PTS 修复标志
    private var recreatePts = false
    private var previousPts: Long = 0

    // 协程作用域
    private val encoderScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // 编码器任务
    private var encoderJob: Job? = null

    /**
     * MediaCodec 事件的密封类
     * 使用密封类提供类型安全的事件处理
     */
    private sealed class CodecEvent {
        /** 输入缓冲区可用 */
        data class InputAvailable(val index: Int) : CodecEvent()

        /** 输出缓冲区可用 */
        data class OutputAvailable(
            val index: Int,
            val bufferInfo: MediaCodec.BufferInfo
        ) : CodecEvent()

        /** 输出格式变化 */
        data class FormatChanged(val format: MediaFormat) : CodecEvent()

        /** 编解码器错误 */
        data class Error(val exception: MediaCodec.CodecException) : CodecEvent()
    }

    /**
     * 创建统一的 MediaCodec 事件流
     *
     * 将所有 MediaCodec 回调转换为类型安全的事件流
     */
    private fun createCodecEventFlow(mediaCodec: MediaCodec): Flow<CodecEvent> = callbackFlow {
        val callback = object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                trySend(CodecEvent.InputAvailable(index))
            }

            override fun onOutputBufferAvailable(
                codec: MediaCodec,
                index: Int,
                bufferInfo: MediaCodec.BufferInfo
            ) {
                // 复制 BufferInfo 避免被重用
                val info = MediaCodec.BufferInfo()
                info.set(
                    bufferInfo.offset,
                    bufferInfo.size,
                    bufferInfo.presentationTimeUs,
                    bufferInfo.flags
                )
                trySend(CodecEvent.OutputAvailable(index, info))
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                trySend(CodecEvent.Error(e))
                close(e)
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                trySend(CodecEvent.FormatChanged(format))
            }
        }

        // 注册回调
        mediaCodec.setCallback(callback)

        // 等待流被关闭
        awaitClose {
            Ln.d("Codec event flow closed")
        }
    }

    /**
     * 创建输入处理流
     *
     * 从事件流中筛选出输入事件，读取音频数据并送入编码器
     */
    private fun createInputPipeline(
        eventFlow: Flow<CodecEvent>,
        mediaCodec: MediaCodec,
        capture: AudioCapture
    ): Flow<CodecEvent.InputAvailable> = eventFlow
        .mapNotNull { event ->
            // 只处理输入事件
            when (event) {
                is CodecEvent.InputAvailable -> event
                else -> null
            }
        }
        .onEach { event ->
            // 获取输入缓冲区
            val buffer = mediaCodec.getInputBuffer(event.index)
                ?: throw IOException("Could not get input buffer ${event.index}")

            // 读取音频数据
            val bufferInfo = MediaCodec.BufferInfo()
            val bytesRead = capture.read(buffer, bufferInfo)

            if (bytesRead <= 0) {
                throw IOException("Could not read audio: $bytesRead")
            }

            // 送入编码器
            mediaCodec.queueInputBuffer(
                event.index,
                bufferInfo.offset,
                bufferInfo.size,
                bufferInfo.presentationTimeUs,
                bufferInfo.flags
            )
        }
        .buffer(capacity = 64) // 背压缓冲

    /**
     * 创建输出处理流
     *
     * 从事件流中筛选出输出事件，读取编码数据并发送到网络
     */
    private fun createOutputPipeline(
        eventFlow: Flow<CodecEvent>,
        mediaCodec: MediaCodec
    ): Flow<CodecEvent.OutputAvailable> = eventFlow
        .mapNotNull { event ->
            // 只处理输出事件
            when (event) {
                is CodecEvent.OutputAvailable -> event
                else -> null
            }
        }
        .onEach { event ->
            // 获取输出缓冲区
            val buffer = mediaCodec.getOutputBuffer(event.index)
                ?: throw IOException("Could not get output buffer ${event.index}")

            try {
                // 修复 PTS (如果需要)
                if (recreatePts) {
                    fixTimestamp(event.bufferInfo)
                }

                // 发送到网络
                streamer.writePacket(buffer, event.bufferInfo)
            } finally {
                // 释放输出缓冲区
                mediaCodec.releaseOutputBuffer(event.index, false)
            }
        }
        .buffer(capacity = 64) // 背压缓冲

    /**
     * 创建格式变化监听流
     *
     * 记录输出格式变化（用于调试）
     */
    private fun createFormatChangePipeline(
        eventFlow: Flow<CodecEvent>
    ): Flow<CodecEvent.FormatChanged> = eventFlow
        .mapNotNull { event ->
            when (event) {
                is CodecEvent.FormatChanged -> event
                else -> null
            }
        }
        .onEach { event ->
            Ln.d("Output format changed: ${event.format}")
        }

    /**
     * 创建错误处理流
     *
     * 捕获并处理编解码器错误
     */
    private fun createErrorPipeline(
        eventFlow: Flow<CodecEvent>
    ): Flow<CodecEvent.Error> = eventFlow
        .mapNotNull { event ->
            when (event) {
                is CodecEvent.Error -> event
                else -> null
            }
        }
        .onEach { event ->
            Ln.e("MediaCodec error: ${event.exception.message}")
            throw event.exception
        }

    /**
     * 修复时间戳
     *
     * 某些编码器（OPUS、FLAC）会用样本数覆盖 PTS，
     * 这会忽略音频时钟漂移和静音，需要重新生成 PTS
     */
    private fun fixTimestamp(bufferInfo: MediaCodec.BufferInfo) {
        assert(recreatePts)

        // 配置包，无需修复
        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            return
        }

        val pts = bufferInfo.presentationTimeUs
        if (previousPts != 0L) {
            val now = System.nanoTime() / 1000
            val duration = pts - previousPts
            bufferInfo.presentationTimeUs = now - duration
        }

        previousPts = pts
    }

    /**
     * 主编码流程
     */
    @Throws(IOException::class, ConfigurationException::class, AudioCaptureException::class)
    private suspend fun encode() = withContext(Dispatchers.Default) {
        // 版本检查
        if (Build.VERSION.SDK_INT < AndroidVersions.API_30_ANDROID_11) {
            Ln.w("Audio disabled: it is not supported before Android 11")
            streamer.writeDisableStream(false)
            return@withContext
        }

        var mediaCodec: MediaCodec? = null
        var mediaCodecStarted = false

        try {
            // 检查兼容性
            capture.checkCompatibility()

            // 创建编码器
            val codec = streamer.codec
            mediaCodec = createMediaCodec(codec, encoderName)

            // 检查是否需要重新生成 PTS
            val codecName = mediaCodec.canonicalName
            recreatePts = codecName in listOf(
                "c2.android.opus.encoder",
                "c2.android.flac.encoder"
            )

            // 启动事件流
            val eventFlow = createCodecEventFlow(mediaCodec)
                .shareIn(
                    scope = this,
                    started = SharingStarted.Eagerly,  // 立即启动
                    replay = 0
                )


            // 配置 MediaCodec
            val format = createFormat(codec.mimeType, bitRate, codecOptions)
            mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

            // 启动音频捕获
            capture.start()

            // 启动编码器
            mediaCodec.start()
            mediaCodecStarted = true

            // 发送音频头部
            streamer.writeAudioHeader()

            // 创建所有处理管道
            val inputPipeline = createInputPipeline(eventFlow, mediaCodec, capture)
            val outputPipeline = createOutputPipeline(eventFlow, mediaCodec)
            val formatPipeline = createFormatChangePipeline(eventFlow)
            val errorPipeline = createErrorPipeline(eventFlow)

            // 启动输入处理协程
            val inputJob = launch {
                inputPipeline
                    .catch { e ->
                        if (e !is CancellationException) {
                            Ln.e("Input pipeline error", e)
                            throw e
                        }
                    }
                    .onCompletion { cause ->
                        Ln.d("Input pipeline completed: ${cause?.message ?: "normally"}")
                    }
                    .collect { }
            }

            // 启动输出处理协程
            val outputJob = launch {
                outputPipeline
                    .catch { e ->
                        if (e !is CancellationException && !isBrokenPipe(e as? IOException)) {
                            Ln.e("Output pipeline error", e)
                            throw e
                        }
                    }
                    .onCompletion { cause ->
                        Ln.d("Output pipeline completed: ${cause?.message ?: "normally"}")
                    }
                    .collect { }
            }

            // 启动格式变化监听协程
            val formatJob = launch {
                formatPipeline
                    .catch { e ->
                        Ln.w("Format pipeline error", e)
                    }
                    .collect { }
            }

            // 启动错误处理协程
            val errorJob = launch {
                errorPipeline
                    .catch { e ->
                        Ln.e("Error pipeline failed", e)
                        throw e
                    }
                    .collect { }
            }

            // 等待所有管道完成
            inputJob.join()
            outputJob.join()
            formatJob.join()
            errorJob.join()

        } catch (e: ConfigurationException) {
            streamer.writeDisableStream(true)
            throw e
        } catch (e: Throwable) {
            if (e !is CancellationException) {
                streamer.writeDisableStream(false)
            }
            throw e
        } finally {
            cleanup(mediaCodec, mediaCodecStarted)
        }
    }

    /**
     * 清理资源
     */
    private fun cleanup(mediaCodec: MediaCodec?, mediaCodecStarted: Boolean) {
        // 停止并释放 MediaCodec
        mediaCodec?.let {
            try {
                if (mediaCodecStarted) {
                    it.stop()
                }
                it.release()
            } catch (e: Exception) {
                Ln.w("Error releasing MediaCodec", e)
            }
        }

        // 停止音频捕获
        try {
            capture.stop()
        } catch (e: Exception) {
            Ln.w("Error stopping capture", e)
        }
    }

    // ============================================
    // AsyncProcessor 接口实现
    // ============================================

    override fun start(listener: AsyncProcessor.TerminationListener?) {
        encoderJob = encoderScope.launch {
            var fatalError = false

            try {
                encode()
            } catch (e: CancellationException) {
                Ln.d("Audio encoding cancelled normally")
            } catch (e: ConfigurationException) {
                Ln.e("Configuration error: ${e.message}")
                fatalError = true
            } catch (e: AudioCaptureException) {
                Ln.e("Audio capture exception: ${e.message}")
            } catch (e: IOException) {
                Ln.e("Audio encoding error", e)
                fatalError = true
            } catch (t: Throwable) {
                Ln.e("Unexpected error", t)
                fatalError = true
            } finally {
                Ln.d("Audio encoder stopped")
                listener?.onTerminated(fatalError)
            }
        }
    }

    override fun stop() {
        // 取消编码器协程（会级联取消所有子协程和 Flow）
        encoderJob?.cancel()
        encoderScope.cancel()
    }

    @Throws(InterruptedException::class)
    override fun join() {
        runBlocking {
            encoderJob?.join()
        }
    }

    companion object {
        private const val SAMPLE_RATE = AudioConfig.SAMPLE_RATE
        private const val CHANNELS = AudioConfig.CHANNELS

        /**
         * 创建音频格式
         */
        private fun createFormat(
            mimeType: String?,
            bitRate: Int,
            codecOptions: List<CodecOption>?
        ): MediaFormat {
            val format = MediaFormat()
            format.setString(MediaFormat.KEY_MIME, mimeType)
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            format.setInteger(MediaFormat.KEY_CHANNEL_COUNT, CHANNELS)
            format.setInteger(MediaFormat.KEY_SAMPLE_RATE, SAMPLE_RATE)

            codecOptions?.forEach { option ->
                val key = option.key
                val value = option.value
                setCodecOption(format, key, value)
                Ln.d("Audio codec option set: $key (${value.javaClass.simpleName}) = $value")
            }

            return format
        }

        /**
         * 创建 MediaCodec 编码器
         */
        @Throws(IOException::class, ConfigurationException::class)
        private fun createMediaCodec(codec: Codec, encoderName: String?): MediaCodec {
            if (encoderName != null) {
                Ln.d("Creating audio encoder by name: '$encoderName'")
                try {
                    val mediaCodec = MediaCodec.createByCodecName(encoderName)
                    val mimeType = Codec.getMimeType(mediaCodec)

                    if (codec.mimeType != mimeType) {
                        val message = "Audio encoder type for \"$encoderName\" " +
                                "($mimeType) does not match codec type (${codec.mimeType})"
                        Ln.e(message)
                        throw ConfigurationException("Incorrect encoder type: $encoderName")
                    }

                    return mediaCodec
                } catch (e: IllegalArgumentException) {
                    val message = """
                        Audio encoder '$encoderName' for ${codec.codecName} not found
                        ${buildAudioEncoderListMessage()}
                    """.trimIndent()
                    Ln.e(message)
                    throw ConfigurationException("Unknown encoder: $encoderName")
                } catch (e: IOException) {
                    val message = """
                        Could not create audio encoder '$encoderName' for ${codec.codecName}
                        ${buildAudioEncoderListMessage()}
                    """.trimIndent()
                    Ln.e(message)
                    throw e
                }
            }

            // 使用默认编码器
            try {
                val mediaCodec = MediaCodec.createEncoderByType(codec.mimeType!!)
                Ln.d("Using audio encoder: '${mediaCodec.name}'")
                return mediaCodec
            } catch (e: IOException) {
                val message = """
                    Could not create default audio encoder for ${codec.codecName}
                    ${buildAudioEncoderListMessage()}
                """.trimIndent()
                Ln.e(message)
                throw e
            } catch (e: IllegalArgumentException) {
                val message = """
                    Could not create default audio encoder for ${codec.codecName}
                    ${buildAudioEncoderListMessage()}
                """.trimIndent()
                Ln.e(message)
                throw e
            }
        }
    }
}