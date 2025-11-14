package com.genymobile.scrcpy.audio

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.ComponentName
import android.content.Intent
import android.media.AudioRecord
import android.media.MediaCodec
import android.os.Build
import android.os.SystemClock
import androidx.annotation.RequiresApi
import com.genymobile.scrcpy.AndroidVersions
import com.genymobile.scrcpy.FakeContext
import com.genymobile.scrcpy.FakeContext.Companion.get
import com.genymobile.scrcpy.Workarounds.createAudioRecord
import com.genymobile.scrcpy.audio.AudioConfig.createAudioFormat
import com.genymobile.scrcpy.util.Ln.d
import com.genymobile.scrcpy.util.Ln.e
import com.genymobile.scrcpy.util.Ln.w
import com.genymobile.scrcpy.wrappers.ServiceManager.activityManager
import java.nio.ByteBuffer

//
class AudioDirectCapture(audioSource: AudioSource) : AudioCapture {
    private val audioSource: Int = audioSource.directAudioSource

    private var recorder: AudioRecord? = null
    private var reader: AudioRecordReader? = null

    @RequiresApi(Build.VERSION_CODES.R)
    @Throws(AudioCaptureException::class)
    private fun tryStartRecording(attempts: Int, delayMs: Int) {
        var attempts = attempts
        while (attempts-- > 0) {
            // Wait for activity to start
            SystemClock.sleep(delayMs.toLong())
            try {
                startRecording()
                return  // it worked
            } catch (e: UnsupportedOperationException) {
                if (attempts == 0) {
                    e("Failed to start audio capture")
                    e(
                        "On Android 11, audio capture must be started in the foreground, make sure that the device is unlocked when starting "
                                + "scrcpy."
                    )
                    throw AudioCaptureException()
                } else {
                    d("Failed to start audio capture, retrying...")
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    @Throws(AudioCaptureException::class)
    private fun startRecording() {
        recorder = try {
            createAudioRecord(audioSource)
        } catch (e: NullPointerException) {
            // Creating an AudioRecord using an AudioRecord.Builder does not work on Vivo phones:
            // - <https://github.com/Genymobile/scrcpy/issues/3805>
            // - <https://github.com/Genymobile/scrcpy/pull/3862>
            createAudioRecord(
                audioSource,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                CHANNELS,
                CHANNEL_MASK,
                ENCODING
            )
        }
        recorder!!.startRecording()
        reader = AudioRecordReader(recorder!!)
    }

    @Throws(AudioCaptureException::class)
    override fun checkCompatibility() {
        if (Build.VERSION.SDK_INT < AndroidVersions.API_30_ANDROID_11) {
            w("Audio disabled: it is not supported before Android 11")
            throw AudioCaptureException()
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    @Throws(AudioCaptureException::class)
    override fun start() {
        if (Build.VERSION.SDK_INT == AndroidVersions.API_30_ANDROID_11) {
            startWorkaroundAndroid11()
            try {
                tryStartRecording(5, 100)
            } finally {
                stopWorkaroundAndroid11()
            }
        } else {
            startRecording()
        }
    }

    override fun stop() {
        if (recorder != null) {
            // Will call .stop() if necessary, without throwing an IllegalStateException
            recorder!!.release()
        }
    }

    @TargetApi(AndroidVersions.API_24_ANDROID_7_0)
    override fun read(outDirectBuffer: ByteBuffer, outBufferInfo: MediaCodec.BufferInfo): Int {
        return reader?.read(outDirectBuffer, outBufferInfo) ?: 0
    }

    companion object {
        private const val SAMPLE_RATE = AudioConfig.SAMPLE_RATE
        private const val CHANNEL_CONFIG = AudioConfig.CHANNEL_CONFIG
        private const val CHANNELS = AudioConfig.CHANNELS
        private const val CHANNEL_MASK = AudioConfig.CHANNEL_MASK
        private const val ENCODING = AudioConfig.ENCODING

        @TargetApi(AndroidVersions.API_23_ANDROID_6_0)
        @SuppressLint("WrongConstant", "MissingPermission")
        private fun createAudioRecord(audioSource: Int): AudioRecord {
            val builder = AudioRecord.Builder()
            if (Build.VERSION.SDK_INT >= AndroidVersions.API_31_ANDROID_12) {
                // On older APIs, Workarounds.fillAppInfo() must be called beforehand
                builder.setContext(get())
            }
            builder.setAudioSource(audioSource)
            builder.setAudioFormat(createAudioFormat())
            val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, ENCODING)
            if (minBufferSize > 0) {
                // This buffer size does not impact latency
                builder.setBufferSizeInBytes(8 * minBufferSize)
            }

            return builder.build()
        }

        private fun startWorkaroundAndroid11() {
            // Android 11 requires Apps to be at foreground to record audio.
            // Normally, each App has its own user ID, so Android checks whether the requesting App has the user ID that's at the foreground.
            // But scrcpy server is NOT an App, it's a Java application started from Android shell, so it has the same user ID (2000) with Android
            // shell ("com.android.shell").
            // If there is an Activity from Android shell running at foreground, then the permission system will believe scrcpy is also in the
            // foreground.
            val intent = Intent(Intent.ACTION_MAIN)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.addCategory(Intent.CATEGORY_LAUNCHER)
            intent.setComponent(
                ComponentName(
                    FakeContext.PACKAGE_NAME,
                    "com.android.shell.HeapDumpActivity"
                )
            )
            activityManager!!.startActivity(intent)
        }

        private fun stopWorkaroundAndroid11() {
            activityManager!!.forceStopPackage(FakeContext.PACKAGE_NAME)
        }
    }
}