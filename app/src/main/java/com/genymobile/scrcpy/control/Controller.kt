package com.genymobile.scrcpy.control

import android.content.ClipboardManager.OnPrimaryClipChangedListener
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Pair
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.MotionEvent.PointerCoords
import android.view.MotionEvent.PointerProperties
import androidx.annotation.RequiresApi
import com.genymobile.scrcpy.AndroidVersions
import com.genymobile.scrcpy.AsyncProcessor
import com.genymobile.scrcpy.AsyncProcessor.TerminationListener
import com.genymobile.scrcpy.CleanUp
import com.genymobile.scrcpy.Options
import com.genymobile.scrcpy.device.Device
import com.genymobile.scrcpy.device.Device.clipboardText
import com.genymobile.scrcpy.device.Device.collapsePanels
import com.genymobile.scrcpy.device.Device.expandNotificationPanel
import com.genymobile.scrcpy.device.Device.expandSettingsPanel
import com.genymobile.scrcpy.device.Device.findByName
import com.genymobile.scrcpy.device.Device.findByPackageName
import com.genymobile.scrcpy.device.Device.injectEvent
import com.genymobile.scrcpy.device.Device.injectKeyEvent
import com.genymobile.scrcpy.device.Device.isScreenOn
import com.genymobile.scrcpy.device.Device.pressReleaseKeycode
import com.genymobile.scrcpy.device.Device.rotateDevice
import com.genymobile.scrcpy.device.Device.setClipboardText
import com.genymobile.scrcpy.device.Device.setDisplayPower
import com.genymobile.scrcpy.device.Device.startApp
import com.genymobile.scrcpy.device.Device.supportsInputEvents
import com.genymobile.scrcpy.device.DeviceApp
import com.genymobile.scrcpy.device.Point
import com.genymobile.scrcpy.device.Position
import com.genymobile.scrcpy.device.Size
import com.genymobile.scrcpy.util.Ln
import com.genymobile.scrcpy.util.Ln.d
import com.genymobile.scrcpy.util.Ln.e
import com.genymobile.scrcpy.util.Ln.i
import com.genymobile.scrcpy.util.Ln.isEnabled
import com.genymobile.scrcpy.util.Ln.v
import com.genymobile.scrcpy.util.Ln.w
import com.genymobile.scrcpy.util.LogUtils.buildAppListMessage
import com.genymobile.scrcpy.video.SurfaceCapture
import com.genymobile.scrcpy.video.VirtualDisplayListener
import com.genymobile.scrcpy.wrappers.InputManager.Companion.setActionButton
import com.genymobile.scrcpy.wrappers.ServiceManager.activityManager
import com.genymobile.scrcpy.wrappers.ServiceManager.clipboardManager
import com.genymobile.scrcpy.wrappers.ServiceManager.displayManager
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class Controller(
    private val controlChannel: ControlChannel,
    private val cleanUp: CleanUp?,
    options: Options
) :
    AsyncProcessor, VirtualDisplayListener {
    /*
        * For event injection, there are two display ids:
        *  - the displayId passed to the constructor (which comes from --display-id passed by the client, 0 for the main display);
        *  - the virtualDisplayId used for mirroring, notified by the capture instance via the VirtualDisplayListener interface.
        *
        * (In case the ScreenCapture uses the "SurfaceControl API", then both ids are equals, but this is an implementation detail.)
        *
        * In order to make events work correctly in all cases:
        *  - virtualDisplayId must be used for events relative to the display (mouse and touch events with coordinates);
        *  - displayId must be used for other events (like key events).
        *
        * If a new separate virtual display is created (using --new-display), then displayId == Device.DISPLAY_ID_NONE. In that case, all events are
        * sent to the virtual display id.
        */
    private class DisplayData(val virtualDisplayId: Int, positionMapper: PositionMapper) {
        val positionMapper: PositionMapper = positionMapper
    }

    private var startAppExecutor: ExecutorService? = null

    private var thread: Thread? = null

    private var uhidManager: UhidManager? = null

    private val displayId = options.displayId
    private val supportsInputEvents: Boolean
    private val sender: DeviceMessageSender
    private val clipboardAutosync = options.clipboardAutosync
    private val powerOn = options.powerOn

    private val charMap: KeyCharacterMap = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)

    private val isSettingClipboard = AtomicBoolean()

    private val displayData = AtomicReference<DisplayData>()
    private val displayDataAvailable = Any() // condition variable

    private var lastTouchDown: Long = 0
    private val pointersState: PointersState = PointersState()
    private val pointerProperties = arrayOfNulls<PointerProperties?>(PointersState.MAX_POINTERS)
    private val pointerCoords = arrayOfNulls<PointerCoords?>(PointersState.MAX_POINTERS)

    private var keepDisplayPowerOff = false

    // Used for resetting video encoding on RESET_VIDEO message
    private var surfaceCapture: SurfaceCapture? = null

    init {
        initPointers()
        sender = DeviceMessageSender(controlChannel)

        supportsInputEvents = supportsInputEvents(displayId)
        if (!supportsInputEvents) {
            w("Input events are not supported for secondary displays before Android 10")
        }

        // Make sure the clipboard manager is always created from the main thread (even if clipboardAutosync is disabled)
        val clipboardManager = clipboardManager
        if (clipboardAutosync) {
            // If control and autosync are enabled, synchronize Android clipboard to the computer automatically
            if (clipboardManager != null) {
                clipboardManager.addPrimaryClipChangedListener(OnPrimaryClipChangedListener {
                    if (isSettingClipboard.get()) {
                        // This is a notification for the change we are currently applying, ignore it
                        return@OnPrimaryClipChangedListener
                    }
                    val text = clipboardText
                    if (text != null) {
                        val msg: DeviceMessage = DeviceMessage.createClipboard(text)
                        sender.send(msg)
                    }
                })
            } else {
                w("No clipboard manager, copy-paste between device and computer will not work")
            }
        }
    }

    override fun onNewVirtualDisplay(virtualDisplayId: Int, positionMapper: PositionMapper?) {
        val data = positionMapper?.let { DisplayData(virtualDisplayId, it) }
        val old = displayData.getAndSet(data)
        if (old == null) {
            // The very first time the Controller is notified of a new virtual display
            synchronized(displayDataAvailable) {
                (displayDataAvailable as Object).notify()
            }
        }
    }

    fun setSurfaceCapture(surfaceCapture: SurfaceCapture?) {
        this.surfaceCapture = surfaceCapture
    }

    private fun getUhidManager(): UhidManager? {
        if (uhidManager == null) {
            var uhidDisplayId = displayId
            if (Build.VERSION.SDK_INT >= AndroidVersions.API_35_ANDROID_15) {
                if (displayId == Device.DISPLAY_ID_NONE) {
                    // Mirroring a new virtual display id (using --new-display-id feature) on Android >= 15, where the UHID mouse pointer can be
                    // associated to the virtual display
                    try {
                        // Wait for at most 1 second until a virtual display id is known
                        val data = waitDisplayData(1000)
                        if (data != null) {
                            uhidDisplayId = data.virtualDisplayId
                        }
                    } catch (e: InterruptedException) {
                        // do nothing
                    }
                }
            }

            var displayUniqueId: String? = null
            if (uhidDisplayId > 0) {
                // Ignore Device.DISPLAY_ID_NONE and 0 (main display)
                val displayInfo = displayManager!!.getDisplayInfo(uhidDisplayId)
                if (displayInfo != null) {
                    displayUniqueId = displayInfo.uniqueId
                }
            }
            uhidManager = UhidManager(sender, displayUniqueId)
        }

        return uhidManager
    }

    private fun initPointers() {
        for (i in 0..<PointersState.MAX_POINTERS) {
            val props = PointerProperties()
            props.toolType = MotionEvent.TOOL_TYPE_FINGER

            val coords = PointerCoords()
            coords.orientation = 0f
            coords.size = 0f

            pointerProperties[i] = props
            pointerCoords[i] = coords
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    @Throws(IOException::class)
    private fun control() {
        // on start, power on the device
        if (powerOn && displayId == 0 && !isScreenOn(displayId)) {
            pressReleaseKeycode(KeyEvent.KEYCODE_POWER, displayId, Device.INJECT_MODE_ASYNC)

            // dirty hack
            // After POWER is injected, the device is powered on asynchronously.
            // To turn the device screen off while mirroring, the client will send a message that
            // would be handled before the device is actually powered on, so its effect would
            // be "canceled" once the device is turned back on.
            // Adding this delay prevents to handle the message before the device is actually
            // powered on.
            SystemClock.sleep(500)
        }

        var alive = true
        while (!Thread.currentThread().isInterrupted && alive) {
            alive = handleEvent()
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun start(listener: TerminationListener?) {
        thread = Thread({
            try {
                control()
            } catch (e: IOException) {
                e("Controller error", e)
            } finally {
                d("Controller stopped")
                uhidManager?.closeAll()
                if (listener != null) {
                    listener.onTerminated(true)
                }
            }
        }, "control-recv")
        thread!!.start()
        sender.start()
    }

    override fun stop() {
        if (thread != null) {
            thread!!.interrupt()
        }
        sender.stop()
    }

    @Throws(InterruptedException::class)
    override fun join() {
        if (thread != null) {
            thread!!.join()
        }
        sender.join()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    @Throws(IOException::class)
    private fun handleEvent(): Boolean {
        val msg: ControlMessage
        try {
            msg = controlChannel.recv()
        } catch (e: IOException) {
            // this is expected on close
            return false
        }

        when (msg.type) {
            ControlMessage.TYPE_INJECT_KEYCODE -> if (supportsInputEvents) {
                injectKeycode(
                    msg.action,
                    msg.keycode,
                    msg.repeat,
                    msg.metaState
                )
            }

            ControlMessage.TYPE_INJECT_TEXT -> if (supportsInputEvents) {
                msg.text?.let { injectText(it) }
            }

            ControlMessage.TYPE_INJECT_TOUCH_EVENT -> if (supportsInputEvents) {
                msg.position?.let {
                    injectTouch(
                        msg.action,
                        msg.pointerId,
                        it,
                        msg.pressure,
                        msg.actionButton,
                        msg.buttons
                    )
                }
            }

            ControlMessage.TYPE_INJECT_SCROLL_EVENT -> if (supportsInputEvents) {
                msg.position?.let {
                    injectScroll(
                        it,
                        msg.hScroll,
                        msg.vScroll,
                        msg.buttons
                    )
                }
            }

            ControlMessage.TYPE_BACK_OR_SCREEN_ON -> if (supportsInputEvents) {
                pressBackOrTurnScreenOn(msg.action)
            }

            ControlMessage.TYPE_EXPAND_NOTIFICATION_PANEL -> expandNotificationPanel()
            ControlMessage.TYPE_EXPAND_SETTINGS_PANEL -> expandSettingsPanel()
            ControlMessage.TYPE_COLLAPSE_PANELS -> collapsePanels()
            ControlMessage.TYPE_GET_CLIPBOARD -> getClipboard(msg.copyKey)
            ControlMessage.TYPE_SET_CLIPBOARD -> setClipboard(
                msg.text,
                msg.paste,
                msg.sequence
            )

            ControlMessage.TYPE_SET_DISPLAY_POWER -> if (supportsInputEvents) {
                setDisplayPower(msg.on)
            }

            ControlMessage.TYPE_ROTATE_DEVICE -> rotateDevice(
                actionDisplayId
            )

            ControlMessage.TYPE_UHID_CREATE -> getUhidManager()?.open(
                msg.id,
                msg.vendorId,
                msg.productId,
                msg.text,
                msg.data
            )

            ControlMessage.TYPE_UHID_INPUT -> getUhidManager()?.writeInput(
                msg.id,
                msg.data
            )

            ControlMessage.TYPE_UHID_DESTROY -> getUhidManager()?.close(msg.id)
            ControlMessage.TYPE_OPEN_HARD_KEYBOARD_SETTINGS -> openHardKeyboardSettings()
            ControlMessage.TYPE_START_APP -> msg.text?.let { startAppAsync(it) }
            ControlMessage.TYPE_RESET_VIDEO -> resetVideo()
            else -> {}
        }

        return true
    }

    private fun injectKeycode(action: Int, keycode: Int, repeat: Int, metaState: Int): Boolean {
        if (keepDisplayPowerOff && action == KeyEvent.ACTION_UP && (keycode == KeyEvent.KEYCODE_POWER || keycode == KeyEvent.KEYCODE_WAKEUP)) {
            assert(displayId != Device.DISPLAY_ID_NONE)
            scheduleDisplayPowerOff(displayId)
        }
        return injectKeyEvent(action, keycode, repeat, metaState, Device.INJECT_MODE_ASYNC)
    }

    private fun injectChar(c: Char): Boolean {
        val decomposed: String = KeyComposition.decompose(c).toString()
        val chars = decomposed?.toCharArray() ?: charArrayOf(c)
        val events = charMap.getEvents(chars) ?: return false

        val actionDisplayId = actionDisplayId
        for (event in events) {
            if (!injectEvent(event, actionDisplayId, Device.INJECT_MODE_ASYNC)) {
                return false
            }
        }
        return true
    }

    private fun injectText(text: String): Int {
        var successCount = 0
        for (c in text.toCharArray()) {
            if (!injectChar(c)) {
                w("Could not inject char u+" + String.format("%04x", c.code))
                continue
            }
            successCount++
        }
        return successCount
    }

    private fun getEventPointAndDisplayId(position: Position): Pair<Point, Int>? {
        // it hides the field on purpose, to read it with atomic access
        val displayData = displayData.get()
        // In scrcpy, displayData should never be null (a touch event can only be generated from the client when a video frame is present).
        // However, it is possible to send events without video playback when using scrcpy-server alone (except for virtual displays).
        assert(displayData != null || displayId != Device.DISPLAY_ID_NONE) { "Cannot receive a positional event without a display" }

        val point: Point
        val targetDisplayId: Int
        if (displayData != null) {
            point = displayData.positionMapper.map(position)!!
            if (point == null) {
                if (isEnabled(Ln.Level.VERBOSE)) {
                    val eventSize = position.screenSize
                    val currentSize: Size = displayData.positionMapper.videoSize
                    v("Ignore positional event generated for size $eventSize (current size is $currentSize)")
                }
                return null
            }
            targetDisplayId = displayData.virtualDisplayId
        } else {
            // No display, use the raw coordinates
            point = position.point
            targetDisplayId = displayId
        }

        return Pair.create(point, targetDisplayId)
    }

    private fun injectTouch(
        action: Int,
        pointerId: Long,
        position: Position,
        pressure: Float,
        actionButton: Int,
        buttons: Int
    ): Boolean {
        var action = action
        var buttons = buttons
        val now = SystemClock.uptimeMillis()

        val pair = getEventPointAndDisplayId(position) ?: return false

        val point = pair.first
        val targetDisplayId = pair.second

        val pointerIndex: Int = pointersState.getPointerIndex(pointerId)
        if (pointerIndex == -1) {
            w("Too many pointers for touch event")
            return false
        }
        val pointer: Pointer = pointersState.get(pointerIndex)
        pointer.point=point
        pointer.pressure=pressure
        val source: Int
        val activeSecondaryButtons =
            ((actionButton or buttons) and MotionEvent.BUTTON_PRIMARY.inv()) != 0
        if (pointerId == POINTER_ID_MOUSE.toLong() && (action == MotionEvent.ACTION_HOVER_MOVE || activeSecondaryButtons)) {
            // real mouse event, or event incompatible with a finger
            pointerProperties[pointerIndex]!!.toolType = MotionEvent.TOOL_TYPE_MOUSE
            source = InputDevice.SOURCE_MOUSE
            pointer.isUp = buttons==0
        } else {
            // POINTER_ID_GENERIC_FINGER, POINTER_ID_VIRTUAL_FINGER or real touch from device
            pointerProperties[pointerIndex]!!.toolType = MotionEvent.TOOL_TYPE_FINGER
            source = InputDevice.SOURCE_TOUCHSCREEN
            // Buttons must not be set for touch events
            buttons = 0
            pointer.isUp = action == MotionEvent.ACTION_UP
        }

        val pointerCount: Int = pointersState.update(pointerProperties, pointerCoords)
        if (pointerCount == 1) {
            if (action == MotionEvent.ACTION_DOWN) {
                lastTouchDown = now
            }
        } else {
            // secondary pointers must use ACTION_POINTER_* ORed with the pointerIndex
            if (action == MotionEvent.ACTION_UP) {
                action =
                    MotionEvent.ACTION_POINTER_UP or (pointerIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            } else if (action == MotionEvent.ACTION_DOWN) {
                action =
                    MotionEvent.ACTION_POINTER_DOWN or (pointerIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            }
        }

        /* If the input device is a mouse (on API >= 23):
         *   - the first button pressed must first generate ACTION_DOWN;
         *   - all button pressed (including the first one) must generate ACTION_BUTTON_PRESS;
         *   - all button released (including the last one) must generate ACTION_BUTTON_RELEASE;
         *   - the last button released must in addition generate ACTION_UP.
         *
         * Otherwise, Chrome does not work properly: <https://github.com/Genymobile/scrcpy/issues/3635>
         */
        if (Build.VERSION.SDK_INT >= AndroidVersions.API_23_ANDROID_6_0 && source == InputDevice.SOURCE_MOUSE) {
            if (action == MotionEvent.ACTION_DOWN) {
                if (actionButton == buttons) {
                    // First button pressed: ACTION_DOWN
                    val downEvent = MotionEvent.obtain(
                        lastTouchDown,
                        now,
                        MotionEvent.ACTION_DOWN,
                        pointerCount,
                        pointerProperties,
                        pointerCoords,
                        0,
                        buttons,
                        1f,
                        1f,
                        DEFAULT_DEVICE_ID,
                        0,
                        source,
                        0
                    )
                    if (!injectEvent(downEvent, targetDisplayId, Device.INJECT_MODE_ASYNC)) {
                        return false
                    }
                }

                // Any button pressed: ACTION_BUTTON_PRESS
                val pressEvent = MotionEvent.obtain(
                    lastTouchDown,
                    now,
                    MotionEvent.ACTION_BUTTON_PRESS,
                    pointerCount,
                    pointerProperties,
                    pointerCoords,
                    0,
                    buttons,
                    1f,
                    1f,
                    DEFAULT_DEVICE_ID,
                    0,
                    source,
                    0
                )
                if (!setActionButton(pressEvent, actionButton)) {
                    return false
                }
                if (!injectEvent(pressEvent, targetDisplayId, Device.INJECT_MODE_ASYNC)) {
                    return false
                }

                return true
            }

            if (action == MotionEvent.ACTION_UP) {
                // Any button released: ACTION_BUTTON_RELEASE
                val releaseEvent = MotionEvent.obtain(
                    lastTouchDown,
                    now,
                    MotionEvent.ACTION_BUTTON_RELEASE,
                    pointerCount,
                    pointerProperties,
                    pointerCoords,
                    0,
                    buttons,
                    1f,
                    1f,
                    DEFAULT_DEVICE_ID,
                    0,
                    source,
                    0
                )
                if (!setActionButton(releaseEvent, actionButton)) {
                    return false
                }
                if (!injectEvent(releaseEvent, targetDisplayId, Device.INJECT_MODE_ASYNC)) {
                    return false
                }

                if (buttons == 0) {
                    // Last button released: ACTION_UP
                    val upEvent = MotionEvent.obtain(
                        lastTouchDown, now, MotionEvent.ACTION_UP, pointerCount, pointerProperties,
                        pointerCoords, 0, buttons, 1f, 1f, DEFAULT_DEVICE_ID, 0, source, 0
                    )
                    if (!injectEvent(upEvent, targetDisplayId, Device.INJECT_MODE_ASYNC)) {
                        return false
                    }
                }

                return true
            }
        }

        val event = MotionEvent.obtain(
            lastTouchDown,
            now,
            action,
            pointerCount,
            pointerProperties,
            pointerCoords,
            0,
            buttons,
            1f,
            1f,
            DEFAULT_DEVICE_ID,
            0,
            source,
            0
        )
        return injectEvent(event, targetDisplayId, Device.INJECT_MODE_ASYNC)
    }

    private fun injectScroll(
        position: Position,
        hScroll: Float,
        vScroll: Float,
        buttons: Int
    ): Boolean {
        val now = SystemClock.uptimeMillis()

        val pair = getEventPointAndDisplayId(position) ?: return false

        val point = pair.first
        val targetDisplayId = pair.second

        val props = pointerProperties[0]
        props!!.id = 0

        val coords = pointerCoords[0]
        coords!!.x = point.x.toFloat()
        coords.y = point.y.toFloat()
        coords.setAxisValue(MotionEvent.AXIS_HSCROLL, hScroll)
        coords.setAxisValue(MotionEvent.AXIS_VSCROLL, vScroll)

        val event = MotionEvent.obtain(
            lastTouchDown,
            now,
            MotionEvent.ACTION_SCROLL,
            1,
            pointerProperties,
            pointerCoords,
            0,
            buttons,
            1f,
            1f,
            DEFAULT_DEVICE_ID,
            0,
            InputDevice.SOURCE_MOUSE,
            0
        )
        return injectEvent(event, targetDisplayId, Device.INJECT_MODE_ASYNC)
    }

    private fun pressBackOrTurnScreenOn(action: Int): Boolean {
        if (displayId == Device.DISPLAY_ID_NONE || isScreenOn(displayId)) {
            return injectKeyEvent(action, KeyEvent.KEYCODE_BACK, 0, 0, Device.INJECT_MODE_ASYNC)
        }

        // Screen is off
        // Only press POWER on ACTION_DOWN
        if (action != KeyEvent.ACTION_DOWN) {
            // do nothing,
            return true
        }

        if (keepDisplayPowerOff) {
            assert(displayId != Device.DISPLAY_ID_NONE)
            scheduleDisplayPowerOff(displayId)
        }
        return pressReleaseKeycode(KeyEvent.KEYCODE_POWER, Device.INJECT_MODE_ASYNC)
    }

    private fun getClipboard(copyKey: Int) {
        // On Android >= 7, press the COPY or CUT key if requested
        if (copyKey != ControlMessage.COPY_KEY_NONE && Build.VERSION.SDK_INT >= AndroidVersions.API_24_ANDROID_7_0 && supportsInputEvents) {
            val key =
                if (copyKey == ControlMessage.COPY_KEY_COPY) KeyEvent.KEYCODE_COPY else KeyEvent.KEYCODE_CUT
            // Wait until the event is finished, to ensure that the clipboard text we read just after is the correct one
            pressReleaseKeycode(key, Device.INJECT_MODE_WAIT_FOR_FINISH)
        }

        // If clipboard autosync is enabled, then the device clipboard is synchronized to the computer clipboard whenever it changes, in
        // particular when COPY or CUT are injected, so it should not be synchronized twice. On Android < 7, do not synchronize at all rather than
        // copying an old clipboard content.
        if (!clipboardAutosync) {
            val clipboardText = clipboardText
            if (clipboardText != null) {
                val msg: DeviceMessage = DeviceMessage.createClipboard(clipboardText)
                sender.send(msg)
            }
        }
    }

    private fun setClipboard(text: String, paste: Boolean, sequence: Long): Boolean {
        isSettingClipboard.set(true)
        val ok = setClipboardText(text)
        isSettingClipboard.set(false)
        if (ok) {
            i("Device clipboard set")
        }

        // On Android >= 7, also press the PASTE key if requested
        if (paste && Build.VERSION.SDK_INT >= AndroidVersions.API_24_ANDROID_7_0 && supportsInputEvents) {
            pressReleaseKeycode(KeyEvent.KEYCODE_PASTE, Device.INJECT_MODE_ASYNC)
        }

        if (sequence != ControlMessage.SEQUENCE_INVALID) {
            // Acknowledgement requested
            val msg: DeviceMessage = DeviceMessage.createAckClipboard(sequence)
            sender.send(msg)
        }

        return ok
    }

    private fun openHardKeyboardSettings() {
        val intent = Intent("android.settings.HARD_KEYBOARD_SETTINGS")
        activityManager!!.startActivity(intent)
    }

    private fun injectKeyEvent(
        action: Int,
        keyCode: Int,
        repeat: Int,
        metaState: Int,
        injectMode: Int
    ): Boolean {
        return injectKeyEvent(
            action, keyCode, repeat, metaState,
            actionDisplayId, injectMode
        )
    }

    private fun pressReleaseKeycode(keyCode: Int, injectMode: Int): Boolean {
        return pressReleaseKeycode(
            keyCode,
            actionDisplayId, injectMode
        )
    }

    private val actionDisplayId: Int
        get() {
            if (displayId != Device.DISPLAY_ID_NONE) {
                // Real screen mirrored, use the source display id
                return displayId
            }

            // Virtual display created by --new-display, use the virtualDisplayId
            val data = displayData.get()
                ?: // If no virtual display id is initialized yet, use the main display id
                return 0

            return data.virtualDisplayId
        }

    private fun startAppAsync(name: String) {
        if (startAppExecutor == null) {
            startAppExecutor = Executors.newSingleThreadExecutor()
        }

        // Listing and selecting the app may take a lot of time
        startAppExecutor!!.submit { startApp(name) }
    }

    private fun startApp(name: String) {
        var name = name
        val forceStopBeforeStart = name.startsWith("+")
        if (forceStopBeforeStart) {
            name = name.substring(1)
        }

        val app: DeviceApp?
        val searchByName = name.startsWith("?")
        if (searchByName) {
            name = name.substring(1)

            i("Processing Android apps... (this may take some time)")
            val apps = findByName(name)
            if (apps.isEmpty()) {
                w("No app found for name \"$name\"")
                return
            }

            if (apps.size > 1) {
                val title = "No unique app found for name \"$name\":"
                w(buildAppListMessage(title, apps))
                return
            }

            app = apps[0]
        } else {
            app = findByPackageName(name)
            if (app == null) {
                w("No app found for package \"$name\"")
                return
            }
        }

        val startAppDisplayId = startAppDisplayId
        if (startAppDisplayId == Device.DISPLAY_ID_NONE) {
            e("No known display id to start app \"$name\"")
            return
        }

        i("Starting app \"" + app.name + "\" [" + app.packageName + "] on display " + startAppDisplayId + "...")
        startApp(app.packageName, startAppDisplayId, forceStopBeforeStart)
    }

    private val startAppDisplayId: Int
        get() {
            if (displayId != Device.DISPLAY_ID_NONE) {
                return displayId
            }

            // Mirroring a new virtual display id (using --new-display-id feature)
            try {
                // Wait for at most 1 second until a virtual display id is known
                val data = waitDisplayData(1000)
                if (data != null) {
                    return data.virtualDisplayId
                }
            } catch (e: InterruptedException) {
                // do nothing
            }

            // No display id available
            return Device.DISPLAY_ID_NONE
        }

    @Throws(InterruptedException::class)
    private fun waitDisplayData(timeoutMillis: Long): DisplayData? {
        val deadline = System.currentTimeMillis() + timeoutMillis

        synchronized(displayDataAvailable) {
            var data = displayData.get()
            while (data == null) {
                val timeout = deadline - System.currentTimeMillis()
                if (timeout < 0) {
                    return null
                }
                if (timeout > 0) {
                    (displayDataAvailable as Object).wait(timeout)
                }
                data = displayData.get()
            }
            return data
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun setDisplayPower(on: Boolean) {
        // Change the power of the main display when mirroring a virtual display
        val targetDisplayId = if (displayId != Device.DISPLAY_ID_NONE) displayId else 0
        val setDisplayPowerOk = setDisplayPower(targetDisplayId, on)
        if (setDisplayPowerOk) {
            // Do not keep display power off for virtual displays: MOD+p must wake up the physical device
            keepDisplayPowerOff = displayId != Device.DISPLAY_ID_NONE && !on
            i("Device display turned " + (if (on) "on" else "off"))
            if (cleanUp != null) {
                val mustRestoreOnExit = !on
                cleanUp.setRestoreDisplayPower(mustRestoreOnExit)
            }
        }
    }

    private fun resetVideo() {
        if (surfaceCapture != null) {
            i("Video capture reset")
            surfaceCapture!!.requestInvalidate()
        }
    }

    companion object {
        private const val DEFAULT_DEVICE_ID = 0

        // control_msg.h values of the pointerId field in inject_touch_event message
        private const val POINTER_ID_MOUSE = -1

        private val EXECUTOR: ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor()

        /**
         * Schedule a call to set display power to off after a small delay.
         */
        private fun scheduleDisplayPowerOff(displayId: Int) {
            EXECUTOR.schedule({
                i("Forcing display off")
                setDisplayPower(displayId, false)
            }, 200, TimeUnit.MILLISECONDS)
        }
    }

}