package com.genymobile.scrcpy.video

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.graphics.Rect
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCaptureSession.CaptureCallback
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaCodec
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.view.Surface
import androidx.annotation.RequiresApi
import com.genymobile.scrcpy.AndroidVersions
import com.genymobile.scrcpy.Options
import com.genymobile.scrcpy.device.ConfigurationException
import com.genymobile.scrcpy.device.Orientation
import com.genymobile.scrcpy.device.Size
import com.genymobile.scrcpy.opengl.AffineOpenGLFilter
import com.genymobile.scrcpy.opengl.OpenGLRunner
import com.genymobile.scrcpy.util.AffineMatrix
import com.genymobile.scrcpy.util.HandlerExecutor
import com.genymobile.scrcpy.util.Ln
import com.genymobile.scrcpy.util.LogUtils
import com.genymobile.scrcpy.wrappers.ServiceManager.cameraManager
import java.io.IOException
import java.util.Arrays
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

class CameraCapture(options: Options) : SurfaceCapture() {
    private val explicitCameraId = options.cameraId
    private val cameraFacing: CameraFacing? = options.cameraFacing
    private val explicitSize = options.cameraSize
    private var maxSize: Int
    private val aspectRatio: CameraAspectRatio?
    private val fps: Int
    private val highSpeed: Boolean
    private val crop: Rect?
    private val captureOrientation: Orientation
    private val angle: Float

    private var cameraId: String? = null
    private var captureSize: Size? = null
    override var size: Size? = null // after OpenGL transforms
        private set

    private var transform: AffineMatrix? = null
    private var glRunner: OpenGLRunner? = null

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var cameraExecutor: Executor? = null

    private val disconnected = AtomicBoolean()

    init {
        this.maxSize = options.maxSize
        this.aspectRatio = options.cameraAspectRatio
        this.fps = options.cameraFps
        this.highSpeed = options.cameraHighSpeed
        this.crop = options.crop
        this.captureOrientation = options.captureOrientation
        checkNotNull(captureOrientation)
        this.angle = options.angle
    }

    @Throws(ConfigurationException::class, IOException::class)
    protected override fun init() {
        cameraThread = HandlerThread("camera")
        cameraThread!!.start()
        cameraHandler = Handler(cameraThread!!.looper)
        cameraExecutor = HandlerExecutor(cameraHandler!!)

        try {
            cameraId = selectCamera(explicitCameraId, cameraFacing)
            if (cameraId == null) {
                throw ConfigurationException("No matching camera found")
            }

            Ln.i("Using camera '$cameraId'")
            cameraDevice = openCamera(cameraId!!)
        } catch (e: CameraAccessException) {
            throw IOException(e)
        } catch (e: InterruptedException) {
            throw IOException(e)
        }
    }

    @Throws(IOException::class)
    override fun prepare() {
        try {
            captureSize = selectSize(cameraId!!, explicitSize, maxSize, aspectRatio, highSpeed)
            if (captureSize == null) {
                throw IOException("Could not select camera size")
            }
        } catch (e: CameraAccessException) {
            throw IOException(e)
        }

        val filter: VideoFilter = VideoFilter(captureSize)

        if (crop != null) {
            filter.addCrop(crop, false)
        }

        if (captureOrientation != Orientation.Orient0) {
            filter.addOrientation(captureOrientation)
        }

        filter.addAngle(angle.toDouble())

        transform = filter.inverseTransform
        size = filter.outputSize?.limit(maxSize)?.round8()
    }


    @RequiresApi(Build.VERSION_CODES.S)
    @Throws(IOException::class)
    override fun start(surface: Surface?) {
        var surface = surface
        if (transform != null) {
            assert(glRunner == null)
            val glFilter = AffineOpenGLFilter(transform!!)
            // The transform matrix returned by SurfaceTexture is incorrect for camera capture (it often contains an additional unexpected 90°
            // rotation). Use a vertical flip transform matrix instead.
            glRunner = OpenGLRunner(glFilter, VFLIP_MATRIX)
            surface = captureSize?.let { size?.let { it1 -> surface?.let { it2 ->
                glRunner!!.start(it, it1,
                    it2
                )
            } } }
        }

        try {
            val session = surface?.let { createCaptureSession(cameraDevice!!, it) }
            val request = surface?.let { createCaptureRequest(it) }
            if (session != null) {
                if (request != null) {
                    setRepeatingRequest(session, request)
                }
            }
        } catch (e: CameraAccessException) {
            stop()
            throw IOException(e)
        } catch (e: InterruptedException) {
            stop()
            throw IOException(e)
        }
    }

    override fun stop() {
        if (glRunner != null) {
            glRunner!!.stopAndRelease()
            glRunner = null
        }
    }

    override fun release() {
        if (cameraDevice != null) {
            cameraDevice!!.close()
        }
        if (cameraThread != null) {
            cameraThread!!.quitSafely()
        }
    }

    override fun setMaxSize(maxSize: Int): Boolean {
        if (explicitSize != null) {
            return false
        }

        this.maxSize = maxSize
        return true
    }

    @SuppressLint("MissingPermission")
    @TargetApi(AndroidVersions.API_31_ANDROID_12)
    @Throws(
        CameraAccessException::class,
        InterruptedException::class
    )
    private fun openCamera(id: String): CameraDevice {
        val future = CompletableFuture<CameraDevice>()
        cameraManager!!.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                Ln.d("Camera opened successfully")
                future.complete(camera)
            }

            override fun onDisconnected(camera: CameraDevice) {
                Ln.w("Camera disconnected")
                disconnected.set(true)
                invalidate()
            }

            override fun onError(camera: CameraDevice, error: Int) {
                val cameraAccessExceptionErrorCode = when (error) {
                    ERROR_CAMERA_IN_USE -> CameraAccessException.CAMERA_IN_USE
                    ERROR_MAX_CAMERAS_IN_USE -> CameraAccessException.MAX_CAMERAS_IN_USE
                    ERROR_CAMERA_DISABLED -> CameraAccessException.CAMERA_DISABLED
                    ERROR_CAMERA_DEVICE, ERROR_CAMERA_SERVICE -> CameraAccessException.CAMERA_ERROR
                    else -> CameraAccessException.CAMERA_ERROR
                }
                future.completeExceptionally(CameraAccessException(cameraAccessExceptionErrorCode))
            }
        }, cameraHandler)

        try {
            return future.get()
        } catch (e: ExecutionException) {
            throw (e.cause as CameraAccessException?)!!
        }
    }

    @RequiresApi(AndroidVersions.API_31_ANDROID_12)
    @Throws(
        CameraAccessException::class,
        InterruptedException::class
    )
    private fun createCaptureSession(camera: CameraDevice, surface: Surface): CameraCaptureSession {
        val future = CompletableFuture<CameraCaptureSession>()
        val outputConfig = OutputConfiguration(surface)
        val outputs = Arrays.asList(outputConfig)

        val sessionType =
            if (highSpeed) SessionConfiguration.SESSION_HIGH_SPEED else SessionConfiguration.SESSION_REGULAR
        val sessionConfig = SessionConfiguration(
            sessionType, outputs,
            cameraExecutor!!, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    future.complete(session)
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    future.completeExceptionally(CameraAccessException(CameraAccessException.CAMERA_ERROR))
                }
            })

        camera.createCaptureSession(sessionConfig)

        try {
            return future.get()
        } catch (e: ExecutionException) {
            throw (e.cause as CameraAccessException?)!!
        }
    }

    @Throws(CameraAccessException::class)
    private fun createCaptureRequest(surface: Surface): CaptureRequest {
        val requestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
        requestBuilder.addTarget(surface)

        if (fps > 0) {
            requestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps, fps))
        }

        return requestBuilder.build()
    }

    @RequiresApi(AndroidVersions.API_31_ANDROID_12)
    @Throws(
        CameraAccessException::class,
        InterruptedException::class
    )
    private fun setRepeatingRequest(session: CameraCaptureSession, request: CaptureRequest) {
        val callback: CaptureCallback = object : CaptureCallback() {
            override fun onCaptureStarted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                timestamp: Long,
                frameNumber: Long
            ) {
                // Called for each frame captured, do nothing
            }

            override fun onCaptureFailed(
                session: CameraCaptureSession,
                request: CaptureRequest,
                failure: CaptureFailure
            ) {
                Ln.w("Camera capture failed: frame " + failure.frameNumber)
            }
        }

        if (highSpeed) {
            val highSpeedSession = session as CameraConstrainedHighSpeedCaptureSession
            val requests = highSpeedSession.createHighSpeedRequestList(request)
            highSpeedSession.setRepeatingBurst(requests, callback, cameraHandler)
        } else {
            session.setRepeatingRequest(request, callback, cameraHandler)
        }
    }

    override val isClosed: Boolean
        get() = disconnected.get()

    override fun requestInvalidate() {
        // do nothing (the user could not request a reset anyway for now, since there is no controller for camera mirroring)
    }

    companion object {
        val VFLIP_MATRIX: FloatArray = floatArrayOf(
            1f, 0f, 0f, 0f,  // column 1
            0f, -1f, 0f, 0f,  // column 2
            0f, 0f, 1f, 0f,  // column 3
            0f, 1f, 0f, 1f,  // column 4
        )

        @Throws(CameraAccessException::class, ConfigurationException::class)
        private fun selectCamera(explicitCameraId: String?, cameraFacing: CameraFacing?): String? {
            val cameraManager = cameraManager

            val cameraIds = cameraManager!!.cameraIdList
            if (explicitCameraId != null) {
                if (!Arrays.asList(*cameraIds).contains(explicitCameraId)) {
                    Ln.e(
                        """Camera with id $explicitCameraId not found
${LogUtils.buildCameraListMessage(false)}"""
                    )
                    throw ConfigurationException("Camera id not found")
                }
                return explicitCameraId
            }

            if (cameraFacing == null) {
                // Use the first one
                return if (cameraIds.size > 0) cameraIds[0] else null
            }

            for (cameraId in cameraIds) {
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)

                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)!!
                if (cameraFacing.value === facing) {
                    return cameraId
                }
            }

            // Not found
            return null
        }

        @Throws(
            CameraAccessException::class
        )
        private fun selectSize(
            cameraId: String,
            explicitSize: Size?,
            maxSize: Int,
            aspectRatio: CameraAspectRatio?,
            highSpeed: Boolean
        ): Size? {
            if (explicitSize != null) {
                return explicitSize
            }

            val cameraManager = cameraManager
            val characteristics = cameraManager!!.getCameraCharacteristics(cameraId)

            val configs = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = if (highSpeed) configs!!.highSpeedVideoSizes else configs!!.getOutputSizes(
                MediaCodec::class.java
            )
            if (sizes == null) {
                return null
            }

            var stream = Arrays.stream(sizes)
            if (maxSize > 0) {
                stream =
                    stream.filter { it: android.util.Size -> it.width <= maxSize && it.height <= maxSize }
            }

            val targetAspectRatio = resolveAspectRatio(aspectRatio, characteristics)
            if (targetAspectRatio != null) {
                stream = stream.filter { it: android.util.Size ->
                    val ar = (it.width.toFloat() / it.height)
                    val arRatio = ar / targetAspectRatio
                    arRatio >= 0.9f && arRatio <= 1.1f
                }
            }

            val selected = stream.max { s1: android.util.Size, s2: android.util.Size ->
                // Greater width is better
                var cmp = Integer.compare(s1.width, s2.width)
                if (cmp != 0) {
                    return@max cmp
                }

                if (targetAspectRatio != null) {
                    // Closer to the target aspect ratio is better
                    val ar1 = (s1.width.toFloat() / s1.height)
                    val arRatio1 = ar1 / targetAspectRatio
                    val distance1 = abs((1 - arRatio1).toDouble()).toFloat()

                    val ar2 = (s2.width.toFloat() / s2.height)
                    val arRatio2 = ar2 / targetAspectRatio
                    val distance2 = abs((1 - arRatio2).toDouble()).toFloat()

                    // Reverse the order because lower distance is better
                    cmp = java.lang.Float.compare(distance2, distance1)
                    if (cmp != 0) {
                        return@max cmp
                    }
                }
                Integer.compare(s1.height, s2.height)
            }

            if (selected.isPresent) {
                val size = selected.get()
                return Size(size.width, size.height)
            }

            // Not found
            return null
        }

        private fun resolveAspectRatio(
            ratio: CameraAspectRatio?,
            characteristics: CameraCharacteristics
        ): Float? {
            if (ratio == null) {
                return null
            }

            if (ratio.isSensor) {
                val activeSize =
                    characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                return activeSize!!.width().toFloat() / activeSize.height()
            }

            return ratio.aspectRatio
        }
    }
}