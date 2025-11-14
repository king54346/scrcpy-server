package com.genymobile.scrcpy.video

import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.view.Surface
import androidx.annotation.RequiresApi
import com.genymobile.scrcpy.AndroidVersions
import com.genymobile.scrcpy.Options
import com.genymobile.scrcpy.control.PositionMapper
import com.genymobile.scrcpy.device.Orientation
import com.genymobile.scrcpy.device.Size
import com.genymobile.scrcpy.opengl.AffineOpenGLFilter
import com.genymobile.scrcpy.opengl.OpenGLRunner
import com.genymobile.scrcpy.util.AffineMatrix
import com.genymobile.scrcpy.util.Ln
import com.genymobile.scrcpy.wrappers.ServiceManager.displayManager
import com.genymobile.scrcpy.wrappers.ServiceManager.windowManager
import java.io.IOException

class NewDisplayCapture(vdListener: VirtualDisplayListener?, options: Options) :
    SurfaceCapture() {
    private val vdListener: VirtualDisplayListener? = vdListener
    private val newDisplay = options.newDisplay

    private val displaySizeMonitor: DisplaySizeMonitor = DisplaySizeMonitor()

    private var displayTransform: AffineMatrix? = null
    private var eventTransform: AffineMatrix? = null
    private var glRunner: OpenGLRunner? = null

    private var mainDisplaySize: Size? = null
    private var mainDisplayDpi = 0
    private var maxSize: Int
    private val displayImePolicy: Int
    private val crop: Rect?
    private val captureOrientationLocked: Boolean
    private val captureOrientation: Orientation
    private val angle: Float
    private val vdDestroyContent: Boolean
    private val vdSystemDecorations: Boolean

    private var virtualDisplay: VirtualDisplay? = null

    @get:Synchronized
    override var size: Size? = null
        private set
    private var displaySize: Size? = null // the logical size of the display (including rotation)
    private var physicalSize: Size? = null // the physical size of the display (without rotation)

    private var dpi = 0

    init {
        checkNotNull(newDisplay)
        this.maxSize = options.maxSize
        this.displayImePolicy = options.displayImePolicy
        this.crop = options.crop
        checkNotNull(options.captureOrientationLock)
        this.captureOrientationLocked = options.captureOrientationLock != Orientation.Lock.Unlocked
        this.captureOrientation = options.captureOrientation
        checkNotNull(captureOrientation)
        this.angle = options.angle
        this.vdDestroyContent = options.vDDestroyContent
        this.vdSystemDecorations = options.vDSystemDecorations
    }

    override fun init() {
        displaySize = newDisplay!!.size
        dpi = newDisplay.dpi
        if (displaySize == null || dpi == 0) {
            val displayInfo = displayManager!!.getDisplayInfo(0)
            if (displayInfo != null) {
                mainDisplaySize = displayInfo.size
                if ((displayInfo.rotation % 2) != 0) {
                    mainDisplaySize =
                        mainDisplaySize!!.rotate() // Use the natural device orientation (at rotation 0), not the current one
                }
                mainDisplayDpi = displayInfo.dpi
            } else {
                Ln.w("Main display not found, fallback to 1920x1080 240dpi")
                mainDisplaySize = Size(1920, 1080)
                mainDisplayDpi = 240
            }
        }
    }

    override fun prepare() {
        val displayRotation: Int
        if (virtualDisplay == null) {
            if (!newDisplay!!.hasExplicitSize()) {
                displaySize = mainDisplaySize
            }
            if (!newDisplay.hasExplicitDpi()) {
                dpi = scaleDpi(
                    mainDisplaySize!!, mainDisplayDpi,
                    displaySize!!
                )
            }

            size = displaySize
            displayRotation = 0
            // Set the current display size to avoid an unnecessary call to invalidate()
            displaySizeMonitor.sessionDisplaySize=displaySize
        } else {
            val displayInfo = displayManager!!.getDisplayInfo(
                virtualDisplay!!.display.displayId
            )
            displaySize = displayInfo!!.size
            dpi = displayInfo.dpi
            displayRotation = displayInfo.rotation
        }

        val filter: VideoFilter = VideoFilter(displaySize)

        if (crop != null) {
            val transposed = (displayRotation % 2) != 0
            filter.addCrop(crop, transposed)
        }

        filter.addOrientation(displayRotation, captureOrientationLocked, captureOrientation)
        filter.addAngle(angle.toDouble())

        var filteredSize: Size? = filter.outputSize
        if (filteredSize != null) {
            if (!filteredSize.isMultipleOf8 || (maxSize != 0 && filteredSize.max > maxSize)) {
                if (maxSize != 0) {
                    filteredSize = filteredSize.limit(maxSize)
                }
                filteredSize = filteredSize.round8()
                filter.addResize(filteredSize)
            }
        }

        eventTransform = filter.inverseTransform

        // DisplayInfo gives the oriented size (so videoSize includes the display rotation)
        size = filter.outputSize

        // But the virtual display video always remains in the origin orientation (the video itself is not rotated, so it must rotated manually).
        // This additional display rotation must not be included in the input events transform (the expected coordinates are already in the
        // physical display size)
        physicalSize = if ((displayRotation % 2) == 0) {
            displaySize
        } else {
            displaySize!!.rotate()
        }
        val displayFilter: VideoFilter = VideoFilter(physicalSize)
        displayFilter.addRotation(displayRotation)
        val displayRotationMatrix: AffineMatrix? = displayFilter.inverseTransform

        // Take care of multiplication order:
        //   displayTransform = (FILTER_MATRIX * DISPLAY_FILTER_MATRIX)⁻¹
        //                    = DISPLAY_FILTER_MATRIX⁻¹ * FILTER_MATRIX⁻¹
        //                    = displayRotationMatrix * eventTransform
        displayTransform = AffineMatrix.multiplyAll(displayRotationMatrix, eventTransform)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    fun startNew(surface: Surface?) {
        val virtualDisplayId: Int
        try {
            var flags = (VIRTUAL_DISPLAY_FLAG_PUBLIC
                    or VIRTUAL_DISPLAY_FLAG_PRESENTATION
                    or VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                    or VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH
                    or VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT)
            if (vdDestroyContent) {
                flags = flags or VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL
            }
            if (vdSystemDecorations) {
                flags = flags or VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS
            }
            if (Build.VERSION.SDK_INT >= AndroidVersions.API_33_ANDROID_13) {
                flags = flags or (VIRTUAL_DISPLAY_FLAG_TRUSTED
                        or VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP
                        or VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED
                        or VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED)
                if (Build.VERSION.SDK_INT >= AndroidVersions.API_34_ANDROID_14) {
                    flags = flags or (VIRTUAL_DISPLAY_FLAG_OWN_FOCUS
                            or VIRTUAL_DISPLAY_FLAG_DEVICE_DISPLAY_GROUP)
                }
            }
            virtualDisplay = displayManager
                ?.createNewVirtualDisplay(
                    "scrcpy",
                    displaySize!!.width,
                    displaySize!!.height,
                    dpi,
                    surface,
                    flags
                )
            virtualDisplayId = virtualDisplay!!.display.displayId
            Ln.i("New display: " + displaySize!!.width + "x" + displaySize!!.height + "/" + dpi + " (id=" + virtualDisplayId + ")")

            if (displayImePolicy != -1) {
                windowManager!!.setDisplayImePolicy(virtualDisplayId, displayImePolicy)
            }

            displaySizeMonitor.start(virtualDisplayId) { this.invalidate() }
        } catch (e: Exception) {
            Ln.e("Could not create display", e)
            throw AssertionError("Could not create display")
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    @Throws(IOException::class)
    override fun start(surface: Surface?) {
        var surface = surface
        if (displayTransform != null) {
            assert(glRunner == null)
            val glFilter = AffineOpenGLFilter(displayTransform!!)
            glRunner = OpenGLRunner(glFilter)
            surface = physicalSize?.let { size?.let { it1 -> surface?.let { it2 ->
                glRunner!!.start(it, it1,
                    it2
                )
            } } }
        }

        if (virtualDisplay == null) {
            startNew(surface)
        } else {
            virtualDisplay!!.surface = surface
        }

        if (vdListener != null) {
            val positionMapper = size?.let { PositionMapper.create(it, eventTransform, displaySize) }
            vdListener.onNewVirtualDisplay(virtualDisplay!!.display.displayId, positionMapper)
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

        if (virtualDisplay != null) {
            virtualDisplay!!.release()
            virtualDisplay = null
        }
    }

    @Synchronized
    override fun setMaxSize(newMaxSize: Int): Boolean {
        maxSize = newMaxSize
        return true
    }

    override fun requestInvalidate() {
        invalidate()
    }

    companion object {
        // Internal fields copied from android.hardware.display.DisplayManager
        private const val VIRTUAL_DISPLAY_FLAG_PUBLIC = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
        private const val VIRTUAL_DISPLAY_FLAG_PRESENTATION =
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
        private const val VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY =
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
        private const val VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH = 1 shl 6
        private const val VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT = 1 shl 7
        private const val VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
        private const val VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS = 1 shl 9
        private const val VIRTUAL_DISPLAY_FLAG_TRUSTED = 1 shl 10
        private const val VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP = 1 shl 11
        private const val VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED = 1 shl 12
        private const val VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED = 1 shl 13
        private const val VIRTUAL_DISPLAY_FLAG_OWN_FOCUS = 1 shl 14
        private const val VIRTUAL_DISPLAY_FLAG_DEVICE_DISPLAY_GROUP = 1 shl 15

        private fun scaleDpi(initialSize: Size, initialDpi: Int, size: Size): Int {
            val den = initialSize.max
            val num = size.max
            return initialDpi * num / den
        }
    }
}