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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * 音频编码器 (协程版本)
 *  audioEncoder编码音频  输入缓冲区可用(编码器内部的输入缓冲区有空闲) ---> 记录index到inputTask队列中 ---> 编码 ----> 输出缓冲区可用(getOutputBuffer)----> index和编码结果写入output队列中
 *  1. createMediaCodec
 *  2. 设置MediaCodec 回调 (通过channel发送任务通知协程)
 *  3. 输入pcm编码 mediaCodec.queueInputBuffer (读取channel协程运行)
 *  4. 读取发送  mediaCodec.getOutputBuffer(task.index)  (读取channel协程运行)
 * 使用 MediaCodec 对音频进行编码，采用协程替代多线程模型。
 * 使用 Channel 实现生产者-消费者模式，处理输入和输出缓冲区。
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

    // 任务通道
    private val inputChannel = Channel<InputTask>(capacity = 64)
    private val outputChannel = Channel<OutputTask>(capacity = 64)

    // 协程任务
    private var encoderJob: Job? = null
    private var inputJob: Job? = null
    private var outputJob: Job? = null

    /**
     * 输入任务
     */
    private data class InputTask(val index: Int)

    /**
     * 输出任务
     */
    private data class OutputTask(
        val index: Int,
        val bufferInfo: MediaCodec.BufferInfo
    )

    /**
     * 输入协程：从音频源读取数据并送入编码器
     */
    private suspend fun inputCoroutine(
        mediaCodec: MediaCodec,
        capture: AudioCapture
    ) = coroutineScope {
        val bufferInfo = MediaCodec.BufferInfo()

        for (task in inputChannel) {
            // 检查取消
            if (!isActive) break

            val buffer = mediaCodec.getInputBuffer(task.index)
                ?: throw IOException("Could not get input buffer")

            val bytesRead = capture.read(buffer, bufferInfo)
            if (bytesRead <= 0) {
                throw IOException("Could not read audio: $bytesRead")
            }

            mediaCodec.queueInputBuffer(
                task.index,
                bufferInfo.offset,
                bufferInfo.size,
                bufferInfo.presentationTimeUs,
                bufferInfo.flags
            )
        }
    }

    /**
     * 输出协程：从编码器读取数据并发送到网络
     */
    private suspend fun outputCoroutine(
        mediaCodec: MediaCodec
    ) = coroutineScope {
        streamer.writeAudioHeader()

        for (task in outputChannel) {
            // 检查取消
            if (!isActive) break

            val buffer = mediaCodec.getOutputBuffer(task.index)
                ?: throw IOException("Could not get output buffer")

            try {
                if (recreatePts) {
                    fixTimestamp(task.bufferInfo)
                }
                streamer.writePacket(buffer, task.bufferInfo)
            } finally {
                mediaCodec.releaseOutputBuffer(task.index, false)
            }
        }
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
     * MediaCodec 回调（协程版本）
     */
    private inner class EncoderCallback : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            // 非阻塞发送到 channel
            inputChannel.trySend(InputTask(index))
                .onFailure { Ln.w("Failed to send input task") }
        }

        override fun onOutputBufferAvailable(
            codec: MediaCodec,
            index: Int,
            bufferInfo: MediaCodec.BufferInfo
        ) {
            // 非阻塞发送到 channel
            outputChannel.trySend(OutputTask(index, bufferInfo))
                .onFailure { Ln.w("Failed to send output task") }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Ln.e("MediaCodec error", e)
            // 关闭通道，停止所有协程
            inputChannel.close(e)
            outputChannel.close(e)
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            // 忽略格式变化
        }
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

            // 配置 MediaCodec
            val format = createFormat(codec.mimeType, bitRate, codecOptions)
            mediaCodec.setCallback(EncoderCallback())
            mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

            // 启动音频捕获
            capture.start()

            // 启动编码器
            mediaCodec.start()
            mediaCodecStarted = true

            // 启动输入和输出协程
            coroutineScope {
                inputJob = launch {
                    try {
                        inputCoroutine(mediaCodec, capture)
                    } catch (e: CancellationException) {
                        Ln.d("Input coroutine cancelled")
                        throw e
                    } catch (e: IOException) {
                        Ln.e("Audio capture error", e)
                        throw e
                    }
                }

                outputJob = launch {
                    try {
                        outputCoroutine(mediaCodec)
                    } catch (e: CancellationException) {
                        Ln.d("Output coroutine cancelled")
                        throw e
                    } catch (e: IOException) {
                        if (!isBrokenPipe(e)) {
                            Ln.e("Audio encoding error", e)
                        }
                        throw e
                    }
                }

                // 等待两个协程完成（或被取消）
                inputJob?.join()
                outputJob?.join()
            }

        } catch (e: ConfigurationException) {
            // 通知客户端配置错误
            streamer.writeDisableStream(true)
            throw e
        } catch (e: Throwable) {
            // 通知客户端音频无法捕获
            if (e !is CancellationException) {
                streamer.writeDisableStream(false)
            }
            throw e
        } finally {
            // 清理资源
            cleanup(mediaCodec, mediaCodecStarted)
        }
    }

    /**
     * 清理资源
     */
    private fun cleanup(mediaCodec: MediaCodec?, mediaCodecStarted: Boolean) {
        // 关闭通道
        inputChannel.close()
        outputChannel.close()

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
        // 取消所有协程
        inputJob?.cancel()
        outputJob?.cancel()
        encoderJob?.cancel()

        // 关闭通道
        inputChannel.close()
        outputChannel.close()
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