package com.genymobile.scrcpy.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import com.genymobile.scrcpy.AndroidVersions
import com.genymobile.scrcpy.AsyncProcessor
import com.genymobile.scrcpy.AsyncProcessor.TerminationListener
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
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

class SurfaceEncoder(
    private val capture: SurfaceCapture,
    private val streamer: Streamer,
    options: Options
) :
    AsyncProcessor {
    private val encoderName = options.videoEncoder

    private val codecOptions = options.videoCodecOptions

    private val videoBitRate = options.videoBitRate
    private val maxFps = options.maxFps
    private val downsizeOnError = options.downsizeOnError

    private var firstFrameSent = false
    private var consecutiveErrors = 0

    private var thread: Thread? = null
    private val stopped = AtomicBoolean()

    private val reset = CaptureReset()

    @Throws(IOException::class, ConfigurationException::class)
    private fun streamCapture() {
        val codec = streamer.codec
        val mediaCodec = createMediaCodec(codec, encoderName)
        val format = codec.mimeType?.let { createFormat(it, videoBitRate, maxFps, codecOptions) }

        capture.init(reset)

        try {
            var alive: Boolean
            var headerWritten = false

            do {
                reset.consumeReset() // If a capture reset was requested, it is implicitly fulfilled
                capture.prepare()
                val size = capture.size
                if (!headerWritten) {
                    streamer.writeVideoHeader(size!!)
                    headerWritten = true
                }

                if (format != null) {
                    format.setInteger(MediaFormat.KEY_WIDTH, size!!.width)
                }
                if (format != null) {
                    if (size != null) {
                        format.setInteger(MediaFormat.KEY_HEIGHT, size.height)
                    }
                }

                var surface: Surface? = null
                var mediaCodecStarted = false
                var captureStarted = false
                try {
                    mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    surface = mediaCodec.createInputSurface()

                    capture.start(surface)
                    captureStarted = true

                    mediaCodec.start()
                    mediaCodecStarted = true

                    // Set the MediaCodec instance to "interrupt" (by signaling an EOS) on reset
                    reset.setRunningMediaCodec(mediaCodec)

                    if (stopped.get()) {
                        alive = false
                    } else {
                        val resetRequested = reset.consumeReset()
                        if (!resetRequested) {
                            // If a reset is requested during encode(), it will interrupt the encoding by an EOS
                            encode(mediaCodec, streamer)
                        }
                        // The capture might have been closed internally (for example if the camera is disconnected)
                        alive = !stopped.get() && !capture.isClosed
                    }
                } catch (e: IllegalStateException) {
                    if (IO.isBrokenPipe(e)) {
                        // Do not retry on broken pipe, which is expected on close because the socket is closed by the client
                        throw e
                    }
                    Ln.e("Capture/encoding error: " + e.javaClass.name + ": " + e.message)
                    if (!size?.let { prepareRetry(it) }!!) {
                        throw e
                    }
                    alive = true
                } catch (e: IllegalArgumentException) {
                    if (IO.isBrokenPipe(e)) {
                        throw e
                    }
                    Ln.e("Capture/encoding error: " + e.javaClass.name + ": " + e.message)
                    if (!size?.let { prepareRetry(it) }!!) {
                        throw e
                    }
                    alive = true
                } catch (e: IOException) {
                    if (IO.isBrokenPipe(e)) {
                        throw e
                    }
                    Ln.e("Capture/encoding error: " + e.javaClass.name + ": " + e.message)
                    if (!size?.let { prepareRetry(it) }!!) {
                        throw e
                    }
                    alive = true
                } finally {
                    reset.setRunningMediaCodec(null)
                    if (captureStarted) {
                        capture.stop()
                    }
                    if (mediaCodecStarted) {
                        try {
                            mediaCodec.stop()
                        } catch (e: IllegalStateException) {
                            // ignore (just in case)
                        }
                    }
                    mediaCodec.reset()
                    surface?.release()
                }
            } while (alive)
        } finally {
            mediaCodec.release()
            capture.release()
        }
    }

    private fun prepareRetry(currentSize: Size): Boolean {
        if (firstFrameSent) {
            ++consecutiveErrors
            if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                // Definitively fail
                return false
            }

            // Wait a bit to increase the probability that retrying will fix the problem
            SystemClock.sleep(50)
            return true
        }

        if (!downsizeOnError) {
            // Must fail immediately
            return false
        }

        // Downsizing on error is only enabled if an encoding failure occurs before the first frame (downsizing later could be surprising)
        val newMaxSize = chooseMaxSizeFallback(currentSize)
        if (newMaxSize == 0) {
            // Must definitively fail
            return false
        }

        val accepted = capture.setMaxSize(newMaxSize)
        if (!accepted) {
            return false
        }

        // Retry with a smaller size
        Ln.i("Retrying with -m$newMaxSize...")
        return true
    }

    @Throws(IOException::class)
    private fun encode(codec: MediaCodec, streamer: Streamer) {
        val bufferInfo = MediaCodec.BufferInfo()

        var eos: Boolean
        do {
            val outputBufferId = codec.dequeueOutputBuffer(bufferInfo, -1)
            try {
                eos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                // On EOS, there might be data or not, depending on bufferInfo.size
                if (outputBufferId >= 0 && bufferInfo.size > 0) {
                    val codecBuffer = codec.getOutputBuffer(outputBufferId)

                    val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    if (!isConfig) {
                        // If this is not a config packet, then it contains a frame
                        firstFrameSent = true
                        consecutiveErrors = 0
                    }

                    streamer.writePacket(codecBuffer!!, bufferInfo)
                }
            } finally {
                if (outputBufferId >= 0) {
                    codec.releaseOutputBuffer(outputBufferId, false)
                }
            }
        } while (!eos)
    }

    override fun start(listener: TerminationListener?) {
        thread = Thread({
            // Some devices (Meizu) deadlock if the video encoding thread has no Looper
            // <https://github.com/Genymobile/scrcpy/issues/4143>
            Looper.prepare()
            try {
                streamCapture()
            } catch (e: ConfigurationException) {
                // Do not print stack trace, a user-friendly error-message has already been logged
            } catch (e: IOException) {
                // Broken pipe is expected on close, because the socket is closed by the client
                if (!IO.isBrokenPipe(e)) {
                    Ln.e("Video encoding error", e)
                }
            } finally {
                Ln.d("Screen streaming stopped")
                listener?.onTerminated(true)
            }
        }, "video")
        thread!!.start()
    }

    override fun stop() {
        if (thread != null) {
            stopped.set(true)
            reset.reset()
        }
    }

    @Throws(InterruptedException::class)
    override fun join() {
        if (thread != null) {
            thread!!.join()
        }
    }

    companion object {
        private const val DEFAULT_I_FRAME_INTERVAL = 10 // seconds
        private const val REPEAT_FRAME_DELAY_US = 100000 // repeat after 100ms
        private const val KEY_MAX_FPS_TO_ENCODER = "max-fps-to-encoder"

        // Keep the values in descending order
        private val MAX_SIZE_FALLBACK = intArrayOf(2560, 1920, 1600, 1280, 1024, 800)
        private const val MAX_CONSECUTIVE_ERRORS = 3

        private fun chooseMaxSizeFallback(failedSize: Size): Int {
            val currentMaxSize =
                max(failedSize.width.toDouble(), failedSize.height.toDouble()).toInt()
            for (value in MAX_SIZE_FALLBACK) {
                if (value < currentMaxSize) {
                    // We found a smaller value to reduce the video size
                    return value
                }
            }
            // No fallback, fail definitively
            return 0
        }

        @Throws(IOException::class, ConfigurationException::class)
        private fun createMediaCodec(codec: Codec, encoderName: String?): MediaCodec {
            if (encoderName != null) {
                Ln.d("Creating encoder by name: '$encoderName'")
                try {
                    val mediaCodec = MediaCodec.createByCodecName(encoderName)
                    val mimeType = Codec.getMimeType(mediaCodec)
                    if (codec.mimeType != mimeType) {
                        Ln.e("Video encoder type for \"" + encoderName + "\" (" + mimeType + ") does not match codec type (" + codec.mimeType + ")")
                        throw ConfigurationException("Incorrect encoder type: $encoderName")
                    }
                    return mediaCodec
                } catch (e: IllegalArgumentException) {
                    Ln.e(
                        """Video encoder '$encoderName' for ${codec.codecName} not found
${LogUtils.buildVideoEncoderListMessage()}"""
                    )
                    throw ConfigurationException("Unknown encoder: $encoderName")
                } catch (e: IOException) {
                    Ln.e(
                        """
                            Could not create video encoder '$encoderName' for ${codec.codecName}
                            ${LogUtils.buildVideoEncoderListMessage()}
                            """.trimIndent()
                    )
                    throw e
                }
            }

            try {
                val mediaCodec = codec.mimeType?.let { MediaCodec.createEncoderByType(it) }
                if (mediaCodec != null) {
                    Ln.d("Using video encoder: '" + mediaCodec.name + "'")
                }
                return mediaCodec!!
            } catch (e: IOException) {
                Ln.e(
                    """
                        Could not create default video encoder for ${codec.codecName}
                        ${LogUtils.buildVideoEncoderListMessage()}
                        """.trimIndent()
                )
                throw e
            } catch (e: IllegalArgumentException) {
                Ln.e(
                    """
                        Could not create default video encoder for ${codec.codecName}
                        ${LogUtils.buildVideoEncoderListMessage()}
                        """.trimIndent()
                )
                throw e
            }
        }

        private fun createFormat(
            videoMimeType: String,
            bitRate: Int,
            maxFps: Float,
            codecOptions: List<CodecOption>?
        ): MediaFormat {
            val format = MediaFormat()
            format.setString(MediaFormat.KEY_MIME, videoMimeType)
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            // must be present to configure the encoder, but does not impact the actual frame rate, which is variable
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 60)
            format.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            if (Build.VERSION.SDK_INT >= AndroidVersions.API_24_ANDROID_7_0) {
                format.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            }
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, DEFAULT_I_FRAME_INTERVAL)
            // display the very first frame, and recover from bad quality when no new frames
            format.setLong(
                MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER,
                REPEAT_FRAME_DELAY_US.toLong()
            ) // µs
            if (maxFps > 0) {
                // The key existed privately before Android 10:
                // <https://android.googlesource.com/platform/frameworks/base/+/625f0aad9f7a259b6881006ad8710adce57d1384%5E%21/>
                // <https://github.com/Genymobile/scrcpy/issues/488#issuecomment-567321437>
                format.setFloat(KEY_MAX_FPS_TO_ENCODER, maxFps)
            }

            if (codecOptions != null) {
                for (option in codecOptions) {
                    val key = option.key
                    val value = option.value
                    CodecUtils.setCodecOption(format, key, value)
                    Ln.d("Video codec option set: " + key + " (" + value.javaClass.simpleName + ") = " + value)
                }
            }

            return format
        }
    }
}