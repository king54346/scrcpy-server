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
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class Controller(
    private val controlChannel: ControlChannel,
    private val cleanUp: CleanUp?,
    options: Options
) : AsyncProcessor, VirtualDisplayListener {

    private class DisplayData(val virtualDisplayId: Int, positionMapper: PositionMapper) {
        val positionMapper: PositionMapper = positionMapper
    }

    // 协程作用域
    private val controllerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 用于延迟关闭屏幕的协程作用域（模拟原版的静态ScheduledExecutorService）
    private val schedulerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 启动应用的协程调度器（单线程，等价于原版的Executors.newSingleThreadExecutor()）
    @OptIn(ExperimentalCoroutinesApi::class)
    private val startAppDispatcher = Dispatchers.IO.limitedParallelism(1)

    private var controlJob: Job? = null

    private var uhidManager: UhidManager? = null

    private val displayId = options.displayId
    private val supportsInputEvents: Boolean
    private val sender: DeviceMessageSender
    private val clipboardAutosync = options.clipboardAutosync
    private val powerOn = options.powerOn

    val charMap = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
    private val isSettingClipboard = AtomicBoolean()

    private val displayData = AtomicReference<DisplayData>()
    private val displayDataChannel = Channel<DisplayData>(Channel.CONFLATED)

    private var lastTouchDown: Long = 0
    private val pointersState: PointersState = PointersState()
    private val pointerProperties = arrayOfNulls<PointerProperties?>(PointersState.MAX_POINTERS)
    private val pointerCoords = arrayOfNulls<PointerCoords?>(PointersState.MAX_POINTERS)

    private var keepDisplayPowerOff = false

    private var surfaceCapture: SurfaceCapture? = null

    init {
        initPointers()
        sender = DeviceMessageSender(controlChannel)

        supportsInputEvents = supportsInputEvents(displayId)
        if (!supportsInputEvents) {
            w("Input events are not supported for secondary displays before Android 10")
        }

        val clipboardManager = clipboardManager
        if (clipboardAutosync) {
            if (clipboardManager != null) {
                clipboardManager.addPrimaryClipChangedListener(OnPrimaryClipChangedListener {
                    if (isSettingClipboard.get()) {
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
            displayDataChannel.trySend(data!!)
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
                    runBlocking {
                        try {
                            val data = waitDisplayData(1000)
                            if (data != null) {
                                uhidDisplayId = data.virtualDisplayId
                            }
                        } catch (e: TimeoutCancellationException) {
                            // do nothing
                        }
                    }
                }
            }

            var displayUniqueId: String? = null
            if (uhidDisplayId > 0) {
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
    private suspend fun control() {
        // on start, power on the device
        if (powerOn && displayId == 0 && !isScreenOn(displayId)) {
            pressReleaseKeycode(KeyEvent.KEYCODE_POWER, displayId, Device.INJECT_MODE_ASYNC)

            // dirty hack
            delay(500)
        }

        var alive = true
        while (currentCoroutineContext().isActive && alive) {
            alive = handleEvent()
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun start(listener: AsyncProcessor.TerminationListener?) {
        controlJob = controllerScope.launch {
            try {
                control()
            } catch (e: IOException) {
                e("Controller error", e)
            } finally {
                d("Controller stopped")
                uhidManager?.closeAll()
                listener?.onTerminated(true)
            }
        }
        sender.start()
    }

    override fun stop() {
        controlJob?.cancel()
        sender.stop()
    }

    @Throws(InterruptedException::class)
    override fun join() {
        runBlocking {
            controlJob?.join()
        }
        sender.join()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun handleEvent(): Boolean {
        val msg: ControlMessage
        try {
            msg = withContext(Dispatchers.IO) {
                controlChannel.recv()
            }
        } catch (e: IOException) {
            // this is expected on close
            return false
        }

        when (msg.type) {
            // 英文字母
            ControlMessage.TYPE_INJECT_KEYCODE -> if (supportsInputEvents) {
                injectKeycode(
                    msg.action,
                    msg.keycode,
                    msg.repeat,
                    msg.metaState
                )
            }
            //
            ControlMessage.TYPE_INJECT_TEXT -> if (supportsInputEvents) {
                injectText(msg.text)
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
            // alt+o 关闭手机屏幕 (turn screen OFF)
            // alt+shift+o 打开手机屏幕
            // alt+p 打开虚拟屏幕
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
            ControlMessage.TYPE_START_APP -> msg.text.let { startAppAsync(it) }
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
    // 数字键等
    private fun injectChar(c: Char): Boolean {
        val decomposed: String = KeyComposition.decompose(c) ?: c.toString()
        val chars = decomposed.toCharArray()
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
        val displayData = displayData.get()
        assert(displayData != null || displayId != Device.DISPLAY_ID_NONE) { "Cannot receive a positional event without a display" }

        val point: Point
        val targetDisplayId: Int
        if (displayData != null) {
            val mappedPoint = displayData.positionMapper.map(position)
            if (mappedPoint == null) {
                if (isEnabled(Ln.Level.VERBOSE)) {
                    val eventSize = position.screenSize
                    val currentSize: Size = displayData.positionMapper.videoSize
                    v("Ignore positional event generated for size $eventSize (current size is $currentSize)")
                }
                return null
            }
            point = mappedPoint
            targetDisplayId = displayData.virtualDisplayId
        } else {
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
        pointer.point = point
        pointer.pressure = pressure
        val source: Int
        val activeSecondaryButtons =
            ((actionButton or buttons) and MotionEvent.BUTTON_PRIMARY.inv()) != 0
        if (pointerId == POINTER_ID_MOUSE.toLong() && (action == MotionEvent.ACTION_HOVER_MOVE || activeSecondaryButtons)) {
            pointerProperties[pointerIndex]!!.toolType = MotionEvent.TOOL_TYPE_MOUSE
            source = InputDevice.SOURCE_MOUSE
            pointer.isUp = buttons == 0
        } else {
            pointerProperties[pointerIndex]!!.toolType = MotionEvent.TOOL_TYPE_FINGER
            source = InputDevice.SOURCE_TOUCHSCREEN
            buttons = 0
            pointer.isUp = action == MotionEvent.ACTION_UP
        }

        val pointerCount: Int = pointersState.update(pointerProperties, pointerCoords)
        if (pointerCount == 1) {
            if (action == MotionEvent.ACTION_DOWN) {
                lastTouchDown = now
            }
        } else {
            if (action == MotionEvent.ACTION_UP) {
                action =
                    MotionEvent.ACTION_POINTER_UP or (pointerIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            } else if (action == MotionEvent.ACTION_DOWN) {
                action =
                    MotionEvent.ACTION_POINTER_DOWN or (pointerIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            }
        }

        if (source == InputDevice.SOURCE_MOUSE) {
            if (action == MotionEvent.ACTION_DOWN) {
                if (actionButton == buttons) {
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

        if (action != KeyEvent.ACTION_DOWN) {
            return true
        }

        if (keepDisplayPowerOff) {
            assert(displayId != Device.DISPLAY_ID_NONE)
            scheduleDisplayPowerOff(displayId)
        }
        return pressReleaseKeycode(KeyEvent.KEYCODE_POWER, Device.INJECT_MODE_ASYNC)
    }

    private fun getClipboard(copyKey: Int) {
        if (copyKey != ControlMessage.COPY_KEY_NONE && supportsInputEvents) {
            val key =
                if (copyKey == ControlMessage.COPY_KEY_COPY) KeyEvent.KEYCODE_COPY else KeyEvent.KEYCODE_CUT
            pressReleaseKeycode(key, Device.INJECT_MODE_WAIT_FOR_FINISH)
        }

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

        if (paste && supportsInputEvents) {
            pressReleaseKeycode(KeyEvent.KEYCODE_PASTE, Device.INJECT_MODE_ASYNC)
        }

        if (sequence != ControlMessage.SEQUENCE_INVALID) {
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
                return displayId
            }

            val data = displayData.get()
                ?: return 0

            return data.virtualDisplayId
        }

    private fun startAppAsync(name: String) {
        controllerScope.launch(startAppDispatcher) {
            startApp(name)
        }
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

            return runBlocking {
                try {
                    val data = waitDisplayData(1000)
                    data?.virtualDisplayId ?: Device.DISPLAY_ID_NONE
                } catch (e: TimeoutCancellationException) {
                    Device.DISPLAY_ID_NONE
                }
            }
        }

    private suspend fun waitDisplayData(timeoutMillis: Long): DisplayData? {
        return withTimeoutOrNull(timeoutMillis) {
            displayData.get() ?: displayDataChannel.receive()
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun setDisplayPower(on: Boolean) {
        val targetDisplayId = if (displayId != Device.DISPLAY_ID_NONE) displayId else 0
        val setDisplayPowerOk = setDisplayPower(targetDisplayId, on)
        if (setDisplayPowerOk) {
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
        private const val POINTER_ID_MOUSE = -1

        @OptIn(DelicateCoroutinesApi::class)
        private fun scheduleDisplayPowerOff(displayId: Int) {
            GlobalScope.launch {
                delay(200)
                i("Forcing display off")
                setDisplayPower(displayId, false)
            }
        }
    }
}