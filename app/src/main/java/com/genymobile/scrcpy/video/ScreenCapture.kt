package com.genymobile.scrcpy.video

import android.graphics.Rect
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.IBinder
import android.view.Surface
import com.genymobile.scrcpy.AndroidVersions
import com.genymobile.scrcpy.Options
import com.genymobile.scrcpy.control.PositionMapper
import com.genymobile.scrcpy.device.ConfigurationException
import com.genymobile.scrcpy.device.Device
import com.genymobile.scrcpy.device.DisplayInfo
import com.genymobile.scrcpy.device.Orientation
import com.genymobile.scrcpy.device.Size
import com.genymobile.scrcpy.opengl.AffineOpenGLFilter
import com.genymobile.scrcpy.opengl.OpenGLRunner
import com.genymobile.scrcpy.util.AffineMatrix
import com.genymobile.scrcpy.util.Ln
import com.genymobile.scrcpy.util.LogUtils
import com.genymobile.scrcpy.wrappers.ServiceManager.displayManager
import com.genymobile.scrcpy.wrappers.SurfaceControl.closeTransaction
import com.genymobile.scrcpy.wrappers.SurfaceControl.createDisplay
import com.genymobile.scrcpy.wrappers.SurfaceControl.destroyDisplay
import com.genymobile.scrcpy.wrappers.SurfaceControl.openTransaction
import com.genymobile.scrcpy.wrappers.SurfaceControl.setDisplayLayerStack
import com.genymobile.scrcpy.wrappers.SurfaceControl.setDisplayProjection
import com.genymobile.scrcpy.wrappers.SurfaceControl.setDisplaySurface
import java.io.IOException

class ScreenCapture(vdListener: VirtualDisplayListener?, options: Options) :
    SurfaceCapture() {
    private val vdListener: VirtualDisplayListener? = vdListener
    private val displayId = options.displayId
    private var maxSize: Int
    private val crop: Rect?
    private var captureOrientationLock: Orientation.Lock
    private var captureOrientation: Orientation
    private val angle: Float

    private var displayInfo: DisplayInfo? = null
    override var size: Size? = null
        private set

    private val displaySizeMonitor: DisplaySizeMonitor = DisplaySizeMonitor()

    private var display: IBinder? = null
    private var virtualDisplay: VirtualDisplay? = null

    private var transform: AffineMatrix? = null
    private var glRunner: OpenGLRunner? = null

    init {
        assert(displayId != Device.DISPLAY_ID_NONE)
        this.maxSize = options.maxSize
        this.crop = options.crop
        this.captureOrientationLock = options.captureOrientationLock
        this.captureOrientation = options.captureOrientation
        checkNotNull(captureOrientationLock)
        checkNotNull(captureOrientation)
        this.angle = options.angle
    }

    public override fun init() {
        displaySizeMonitor.start(displayId) { this.invalidate() }
    }

    @Throws(ConfigurationException::class)
    override fun prepare() {
        displayInfo = displayManager!!.getDisplayInfo(displayId)
        if (displayInfo == null) {
            Ln.e(
                """Display $displayId not found
${LogUtils.buildDisplayListMessage()}"""
            )
            throw ConfigurationException("Unknown display id: $displayId")
        }

        if ((displayInfo!!.flags and DisplayInfo.FLAG_SUPPORTS_PROTECTED_BUFFERS) == 0) {
            Ln.w("Display doesn't have FLAG_SUPPORTS_PROTECTED_BUFFERS flag, mirroring can be restricted")
        }

        val displaySize = displayInfo!!.size
        displaySizeMonitor.sessionDisplaySize=displaySize

        if (captureOrientationLock == Orientation.Lock.LockedInitial) {
            // The user requested to lock the video orientation to the current orientation
            captureOrientationLock = Orientation.Lock.LockedValue
            captureOrientation = Orientation.fromRotation(displayInfo!!.rotation)
        }

        val filter: VideoFilter = VideoFilter(displaySize)

        if (crop != null) {
            val transposed = (displayInfo!!.rotation % 2) != 0
            filter.addCrop(crop, transposed)
        }

        val locked = captureOrientationLock != Orientation.Lock.Unlocked
        filter.addOrientation(displayInfo!!.rotation, locked, captureOrientation)
        filter.addAngle(angle.toDouble())

        transform = filter.inverseTransform
        size = filter.outputSize?.limit(maxSize)?.round8()
    }

    @Throws(IOException::class)
    override fun start(surface: Surface?) {
        var surface = surface
        if (display != null) {
            destroyDisplay(display)
            display = null
        }
        if (virtualDisplay != null) {
            virtualDisplay!!.release()
            virtualDisplay = null
        }

        val inputSize: Size?
        if (transform != null) {
            // If there is a filter, it must receive the full display content
            inputSize = displayInfo!!.size
            assert(glRunner == null)
            val glFilter = AffineOpenGLFilter(transform!!)
            glRunner = OpenGLRunner(glFilter)
            surface = size?.let { surface?.let { it1 -> glRunner!!.start(inputSize, it, it1) } }
        } else {
            // If there is no filter, the display must be rendered at target video size directly
            inputSize = size
        }

        try {
            virtualDisplay = displayManager
                ?.createVirtualDisplay(
                    "scrcpy",
                    inputSize!!.width,
                    inputSize.height,
                    displayId,
                    surface
                )
            Ln.d("Display: using DisplayManager API")
        } catch (displayManagerException: Exception) {
            try {
                display = createDisplay()

                val deviceSize = displayInfo!!.size
                val layerStack = displayInfo!!.layerStack
                setDisplaySurface(
                    display!!,
                    surface,
                    deviceSize.toRect(),
                    inputSize!!.toRect(),
                    layerStack
                )
                Ln.d("Display: using SurfaceControl API")
            } catch (surfaceControlException: Exception) {
                Ln.e("Could not create display using DisplayManager", displayManagerException)
                Ln.e("Could not create display using SurfaceControl", surfaceControlException)
                throw AssertionError("Could not create display")
            }
        }

        vdListener?.let { listener ->
            val currentVirtualDisplay = virtualDisplay

            val (virtualDisplayId, positionMapper) = if (currentVirtualDisplay == null || displayId == 0) {
                // Surface control or main display
                val deviceSize = displayInfo!!.size
                Pair(displayId, size?.let { PositionMapper.create(it, transform, deviceSize) })
            } else {
                // Virtual display
                Pair(
                    currentVirtualDisplay.display.displayId,
                    size?.let { PositionMapper.create(it, transform, inputSize) }
                )
            }

            listener.onNewVirtualDisplay(virtualDisplayId, positionMapper)
        }
    }

    override fun stop() {
        if (glRunner != null) {
            glRunner!!.stopAndRelease()
            glRunner = null
        }
    }

    override fun release() {
        displaySizeMonitor.stopAndRelease()

        if (display != null) {
            destroyDisplay(display)
            display = null
        }
        if (virtualDisplay != null) {
            virtualDisplay!!.release()
            virtualDisplay = null
        }
    }

    override fun setMaxSize(newMaxSize: Int): Boolean {
        maxSize = newMaxSize
        return true
    }

    override fun requestInvalidate() {
        invalidate()
    }

    companion object {
        @Throws(Exception::class)
        private fun createDisplay(): IBinder {
            // Since Android 12 (preview), secure displays could not be created with shell permissions anymore.
            // On Android 12 preview, SDK_INT is still R (not S), but CODENAME is "S".
            val secure =
                Build.VERSION.SDK_INT < AndroidVersions.API_30_ANDROID_11 || (Build.VERSION.SDK_INT == AndroidVersions.API_30_ANDROID_11
                        && "S" != Build.VERSION.CODENAME)
            return createDisplay("scrcpy", secure)
        }

        private fun setDisplaySurface(
            display: IBinder,
            surface: Surface?,
            deviceRect: Rect,
            displayRect: Rect,
            layerStack: Int
        ) {
            openTransaction()
            try {
                setDisplaySurface(display, surface)
                setDisplayProjection(display, 0, deviceRect, displayRect)
                setDisplayLayerStack(display, layerStack)
            } finally {
                closeTransaction()
            }
        }
    }
}