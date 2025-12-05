package com.genymobile.scrcpy.audio

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.media.AudioRecord
import android.media.MediaCodec
import android.os.Build
import android.os.SystemClock
import androidx.annotation.RequiresApi
import com.genymobile.scrcpy.AndroidVersions
import com.genymobile.scrcpy.FakeContext
import com.genymobile.scrcpy.Workarounds.createAudioRecord
import com.genymobile.scrcpy.audio.AudioConfig.createAudioFormat
import com.genymobile.scrcpy.util.Ln
import com.genymobile.scrcpy.wrappers.ServiceManager.activityManager
import java.nio.ByteBuffer

/**
 * 音频直接捕获类
 * 负责从指定音频源直接捕获音频数据
 * 通过录音器并设置远程混音音频源
 * 录制设备正在播放的声音(内录)
 *
 * 关键特性:
 * - 支持 Android 11 的前台录音要求, Android 12+ 直接启动录音要求
 * - 处理特定设备(如 Vivo)的兼容性问题
 * - 提供自动重试机制
 */
class AudioDirectCapture(audioSource: AudioSource) : AudioCapture {
    private val audioSource: Int = audioSource.directAudioSource
    private var recorder: AudioRecord? = null
    private var reader: AudioRecordReader? = null

    /**
     * 检查设备兼容性
     * Android 11 以下版本不支持音频捕获
     */
    @Throws(AudioCaptureException::class)
    override fun checkCompatibility() {
        if (Build.VERSION.SDK_INT < AndroidVersions.API_30_ANDROID_11) {
            Ln.w("Audio disabled: not supported before Android 11")
            throw AudioCaptureException()
        }
    }

    /**
     * 启动音频捕获
     * 根据 Android 版本采用不同的启动策略
     */
    @RequiresApi(Build.VERSION_CODES.R)
    @Throws(AudioCaptureException::class)
    override fun start() {
        when {
            Build.VERSION.SDK_INT == AndroidVersions.API_30_ANDROID_11 -> {
                // Android 11 需要特殊处理
                startWithAndroid11Workaround()
            }
            else -> {
                // Android 12+ 直接启动
                startRecording()
            }
        }
    }

    /**
     * Android 11 专用启动方法
     * 需要启动前台 Activity 来获取录音权限
     */
    @RequiresApi(Build.VERSION_CODES.R)
    @Throws(AudioCaptureException::class)
    private fun startWithAndroid11Workaround() {
        var workaroundStarted = false
        try {
            startWorkaroundAndroid11()
            workaroundStarted = true
            tryStartRecording(
                maxAttempts = MAX_RETRY_ATTEMPTS,
                delayMs = RETRY_DELAY_MS
            )
        } finally {
            if (workaroundStarted) {
                stopWorkaroundAndroid11()
            }
        }
    }

    /**
     * 重试启动录音
     *
     * @param maxAttempts 最大尝试次数
     * @param delayMs 每次尝试间隔(毫秒)
     */
    @RequiresApi(Build.VERSION_CODES.R)
    @Throws(AudioCaptureException::class)
    private fun tryStartRecording(maxAttempts: Int, delayMs: Int) {
        repeat(maxAttempts) { attempt ->
            // 等待 Activity 启动
            SystemClock.sleep(delayMs.toLong())

            try {
                startRecording()
                Ln.d("Audio capture started successfully (attempt: ${attempt + 1})")
                return  // 成功,直接返回
            } catch (e: UnsupportedOperationException) {
                val isLastAttempt = (attempt == maxAttempts - 1)

                if (isLastAttempt) {
                    Ln.e("Failed to start audio capture")
                    Ln.e(
                        "On Android 11, audio capture must be started in the foreground. " +
                                "Make sure that the device is unlocked when starting scrcpy."
                    )
                    throw AudioCaptureException()
                } else {
                    Ln.d("Failed to start audio capture, retrying... (${attempt + 1}/$maxAttempts)")
                }
            } catch (e: Exception) {
                Ln.e("Audio capture start exception: ${e.message}", e)
                throw AudioCaptureException()
            }
        }
    }

    /**
     * 创建并启动 AudioRecord
     * 包含 Vivo 设备的降级方案
     */
    @RequiresApi(Build.VERSION_CODES.R)
    @Throws(AudioCaptureException::class)
    private fun startRecording() {
        recorder = try {
            // 首选方案: 使用 Builder API
            createAudioRecordWithBuilder(audioSource)
        } catch (e: NullPointerException) {
            // 降级方案: Vivo 手机兼容性处理
            // 参考: https://github.com/Genymobile/scrcpy/issues/3805
            Ln.w("AudioRecord.Builder creation failed, using fallback (possibly Vivo device)")
            createAudioRecord(
                audioSource,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                CHANNELS,
                CHANNEL_MASK,
                ENCODING
            )
        }

        recorder?.apply {
            startRecording()
            reader = AudioRecordReader(this)
        } ?: throw AudioCaptureException()
    }

    /**
     * 停止音频捕获并释放资源
     */
    override fun stop() {
        reader = null

        recorder?.runCatching {
            // release() 会自动调用 stop(),不会抛出 IllegalStateException
            release()
        }?.onFailure { e ->
            Ln.w("Error stopping audio capture: ${e.message}")
        }

        recorder = null
    }

    /**
     * 读取音频数据到缓冲区
     *
     * @param outDirectBuffer 输出缓冲区
     * @param outBufferInfo 缓冲区信息
     * @return 读取的字节数
     */
    override fun read(outDirectBuffer: ByteBuffer, outBufferInfo: MediaCodec.BufferInfo): Int {
        return reader?.read(outDirectBuffer, outBufferInfo) ?: 0
    }

    companion object {
        // 音频配置常量
        private const val SAMPLE_RATE = AudioConfig.SAMPLE_RATE
        private const val CHANNEL_CONFIG = AudioConfig.CHANNEL_CONFIG
        private const val CHANNELS = AudioConfig.CHANNELS
        private const val CHANNEL_MASK = AudioConfig.CHANNEL_MASK
        private const val ENCODING = AudioConfig.ENCODING

        // 重试配置
        private const val MAX_RETRY_ATTEMPTS = 5
        private const val RETRY_DELAY_MS = 100

        /**
         * 使用 Builder API 创建 AudioRecord
         * Android 12+ 优先使用此方法
         */
        @SuppressLint("WrongConstant", "MissingPermission")
        private fun createAudioRecordWithBuilder(audioSource: Int): AudioRecord {
            return AudioRecord.Builder().apply {
                // Android 12+ 设置 Context
                if (Build.VERSION.SDK_INT >= AndroidVersions.API_31_ANDROID_12) {
                    setContext(FakeContext.get())
                }

                setAudioSource(audioSource)
                setAudioFormat(createAudioFormat())

                // 设置缓冲区大小(不影响延迟,但影响稳定性)
                val minBufferSize = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    ENCODING
                )
                if (minBufferSize > 0) {
                    // 使用 8 倍最小缓冲区以提高稳定性
                    setBufferSizeInBytes(8 * minBufferSize)
                }
            }.build()
        }

        /**
         * Android 11 权限绕过方案
         *
         * 原理: Android 11 要求前台应用才能录音,但 scrcpy server 不是真正的 App,
         * 而是从 shell 启动的 Java 应用,拥有与 shell 相同的 UID (2000)。
         * 通过启动 shell 的 HeapDumpActivity 让系统认为 scrcpy 在前台
         * 当启动 HeapDumpActivity 时,系统认为 shell 包在前台运行,
         * 由于 UID 相同,系统也认为 scrcpy 在前台,从而允许录音
         */
        private fun startWorkaroundAndroid11() {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addCategory(Intent.CATEGORY_LAUNCHER)
                component = ComponentName(
                    FakeContext.PACKAGE_NAME,
                    "com.android.shell.HeapDumpActivity"
                )
            }

            activityManager?.startActivity(intent)
                ?: Ln.w("ActivityManager unavailable, cannot start Android 11 workaround")
        }

        /**
         * 停止 Android 11 权限绕过
         * 强制停止 shell 包以清理 Activity
         */
        private fun stopWorkaroundAndroid11() {
            activityManager?.forceStopPackage(FakeContext.PACKAGE_NAME)
                ?: Ln.w("ActivityManager unavailable, cannot stop Android 11 workaround")
        }
    }
}