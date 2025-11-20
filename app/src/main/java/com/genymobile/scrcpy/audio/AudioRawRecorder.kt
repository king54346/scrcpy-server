package com.genymobile.scrcpy.audio

import android.media.MediaCodec
import android.os.Build
import androidx.annotation.RequiresApi
import com.genymobile.scrcpy.AsyncProcessor
import com.genymobile.scrcpy.device.Streamer
import com.genymobile.scrcpy.util.IO.isBrokenPipe
import com.genymobile.scrcpy.util.Ln
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.ByteBuffer

/**
 * 音频原始数据录制器 (Flow 版本)
 *
 * 使用 Kotlin Flow 实现反应式音频数据流处理:
 * ```
 * 音频捕获 Flow
 *   ↓ 读取原始音频数据
 *   ↓ 检查数据有效性
 *   ↓ 发送到网络
 * ```
 *
 * @property capture 音频捕获器
 * @property streamer 网络流传输器
 */
class AudioRawRecorder(
    private val capture: AudioCapture,
    private val streamer: Streamer
) : AsyncProcessor {

    private val recordScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var recordJob: Job? = null

    /**
     * 音频数据包
     */
    private data class AudioPacket(
        val buffer: ByteBuffer,
        val bufferInfo: MediaCodec.BufferInfo
    )

    /**
     * 创建音频捕获流
     *
     * 持续从音频捕获器读取数据并生成数据包流
     */
    private fun createAudioCaptureFlow(): Flow<AudioPacket> = callbackFlow {
        val buffer = ByteBuffer.allocateDirect(AudioConfig.MAX_READ_SIZE)
        val bufferInfo = MediaCodec.BufferInfo()

        try {
            while (true) {
                // 重置缓冲区
                buffer.position(0)

                // 读取音频数据
                val bytesRead = capture.read(buffer, bufferInfo)

                if (bytesRead < 0) {
                    close(IOException("Could not read audio: $bytesRead"))
                    break
                }

                // 设置缓冲区限制
                buffer.limit(bytesRead)

                // 复制数据（避免缓冲区重用问题）
                val packetBuffer = ByteBuffer.allocateDirect(bytesRead)
                packetBuffer.put(buffer)
                packetBuffer.flip()

                val packetInfo = MediaCodec.BufferInfo()
                packetInfo.set(
                    bufferInfo.offset,
                    bufferInfo.size,
                    bufferInfo.presentationTimeUs,
                    bufferInfo.flags
                )

                // 发送数据包
                trySend(AudioPacket(packetBuffer, packetInfo))
            }
        } catch (e: Exception) {
            close(e)
        }

        awaitClose {
            Ln.d("Audio capture flow closed")
        }
    }

    /**
     * 创建音频发送管道
     *
     * 将音频数据包发送到网络
     */
    private fun createSendPipeline(
        audioFlow: Flow<AudioPacket>
    ): Flow<AudioPacket> = audioFlow
        .onEach { packet ->
            streamer.writePacket(packet.buffer, packet.bufferInfo)
        }
        .buffer(capacity = 64) // 背压缓冲

    /**
     * 录制音频的主流程
     */
    @RequiresApi(Build.VERSION_CODES.R)
    @Throws(IOException::class, AudioCaptureException::class)
    private suspend fun record() = withContext(Dispatchers.IO) {
        try {
            // 启动音频捕获
            try {
                capture.start()
            } catch (t: Throwable) {
                // 通知客户端音频捕获失败
                streamer.writeDisableStream(false)
                throw t
            }

            // 发送音频头部
            streamer.writeAudioHeader()

            // 创建音频流
            val audioFlow = createAudioCaptureFlow()

            // 创建发送管道
            val sendPipeline = createSendPipeline(audioFlow)

            // 收集并处理音频流
            sendPipeline
                .onStart {
                    Ln.d("Audio recording started")
                }
                .catch { e ->
                    when (e) {
                        is CancellationException -> {
                            Ln.d("Audio recording cancelled")
                            throw e
                        }
                        is IOException -> {
                            if (!isBrokenPipe(e)) {
                                Ln.e("Audio capture error", e)
                                throw e
                            }
                        }
                        else -> {
                            Ln.e("Unexpected error in audio pipeline", e)
                            throw e
                        }
                    }
                }
                .onCompletion { cause ->
                    Ln.d("Audio recording completed: ${cause?.message ?: "normally"}")
                }
                .collect { }

        } finally {
            // 停止音频捕获
            try {
                capture.stop()
            } catch (e: Exception) {
                Ln.w("Error stopping capture", e)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    override fun start(listener: AsyncProcessor.TerminationListener?) {
        recordJob = recordScope.launch {
            var fatalError = false

            try {
                record()
            } catch (e: CancellationException) {
                Ln.d("Audio recording cancelled normally")
            } catch (e: AudioCaptureException) {
                Ln.e("Audio capture exception: ${e.message}")
            } catch (t: Throwable) {
                Ln.e("Audio recording error", t)
                fatalError = true
            } finally {
                Ln.d("Audio recorder stopped")
                listener?.onTerminated(fatalError)
            }
        }
    }

    override fun stop() {
        recordJob?.cancel()
        recordScope.cancel()
    }

    @Throws(InterruptedException::class)
    override fun join() {
        runBlocking {
            recordJob?.join()
        }
    }
}