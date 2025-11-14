package com.genymobile.scrcpy.audio

import android.media.MediaCodec
import android.os.Build
import androidx.annotation.RequiresApi
import com.genymobile.scrcpy.AndroidVersions
import com.genymobile.scrcpy.AsyncProcessor
import com.genymobile.scrcpy.device.Streamer
import com.genymobile.scrcpy.util.IO.isBrokenPipe
import com.genymobile.scrcpy.util.Ln
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.IOException
import java.nio.ByteBuffer

/**
 * 音频原始数据录制器 (协程版本)
 *
 * 使用 Kotlin 协程替代传统线程，提供更好的结构化并发和资源管理。
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
     * 录制音频的主循环
     */
    @RequiresApi(Build.VERSION_CODES.R)
    @Throws(IOException::class, AudioCaptureException::class)
    private suspend fun record() {
        // 版本检查

        val buffer = ByteBuffer.allocateDirect(AudioConfig.MAX_READ_SIZE)
        val bufferInfo = MediaCodec.BufferInfo()

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

            // 录制循环 - 使用 coroutineScope 确保在协程上下文中
            coroutineScope {
                while (isActive) {
                    buffer.position(0)
                    val bytesRead = capture.read(buffer, bufferInfo)

                    if (bytesRead < 0) {
                        throw IOException("Could not read audio: $bytesRead")
                    }

                    buffer.limit(bytesRead)
                    streamer.writePacket(buffer, bufferInfo)

                    // 让出执行权
                    yield()
                }
            }

        } catch (e: CancellationException) {
            Ln.d("Audio recording cancelled")
            throw e
        } catch (e: IOException) {
            if (!isBrokenPipe(e)) {
                Ln.e("Audio capture error", e)
            }
        } finally {
            capture.stop()
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
    }

    @Throws(InterruptedException::class)
    override fun join() {
        runBlocking {
            recordJob?.join()
        }
    }
}