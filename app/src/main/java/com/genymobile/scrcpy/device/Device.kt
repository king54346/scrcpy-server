package com.genymobile.scrcpy.device

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import com.genymobile.scrcpy.AndroidVersions
import com.genymobile.scrcpy.FakeContext.Companion.get
import com.genymobile.scrcpy.util.Ln
import com.genymobile.scrcpy.wrappers.DisplayControl
import com.genymobile.scrcpy.wrappers.InputManager
import com.genymobile.scrcpy.wrappers.InputManager.Companion.setDisplayId
import com.genymobile.scrcpy.wrappers.ServiceManager.activityManager
import com.genymobile.scrcpy.wrappers.ServiceManager.clipboardManager
import com.genymobile.scrcpy.wrappers.ServiceManager.displayManager
import com.genymobile.scrcpy.wrappers.ServiceManager.inputManager
import com.genymobile.scrcpy.wrappers.ServiceManager.powerManager
import com.genymobile.scrcpy.wrappers.ServiceManager.statusBarManager
import com.genymobile.scrcpy.wrappers.ServiceManager.windowManager
import com.genymobile.scrcpy.wrappers.SurfaceControl
import com.genymobile.scrcpy.wrappers.SurfaceControl.builtInDisplay
import com.genymobile.scrcpy.wrappers.SurfaceControl.hasGetBuildInDisplayMethod
import com.genymobile.scrcpy.wrappers.SurfaceControl.hasGetPhysicalDisplayIdsMethod
import com.genymobile.scrcpy.wrappers.SurfaceControl.setDisplayPowerMode
import java.util.Locale

object Device {
    const val DISPLAY_ID_NONE: Int = -1

    const val POWER_MODE_OFF: Int = SurfaceControl.POWER_MODE_OFF
    const val POWER_MODE_NORMAL: Int = SurfaceControl.POWER_MODE_NORMAL

    const val INJECT_MODE_ASYNC: Int = InputManager.INJECT_INPUT_EVENT_MODE_ASYNC
    const val INJECT_MODE_WAIT_FOR_RESULT: Int =
        InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT
    const val INJECT_MODE_WAIT_FOR_FINISH: Int =
        InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH

    // The new display power method introduced in Android 15 does not work as expected:
    // <https://github.com/Genymobile/scrcpy/issues/5530>
    private const val USE_ANDROID_15_DISPLAY_POWER = false

    val deviceName: String
        get() = Build.MODEL

    fun supportsInputEvents(displayId: Int): Boolean {
        // main display or any display on Android >= 10
        return displayId == 0 || Build.VERSION.SDK_INT >= AndroidVersions.API_29_ANDROID_10
    }

    fun injectEvent(inputEvent: InputEvent, displayId: Int, injectMode: Int): Boolean {
        if (!supportsInputEvents(displayId)) {
            throw AssertionError("Could not inject input event if !supportsInputEvents()")
        }

        if (displayId != 0 && !setDisplayId(inputEvent, displayId)) {
            return false
        }

        return inputManager!!.injectInputEvent(inputEvent, injectMode)
    }

    fun injectKeyEvent(
        action: Int,
        keyCode: Int,
        repeat: Int,
        metaState: Int,
        displayId: Int,
        injectMode: Int
    ): Boolean {
        val now = SystemClock.uptimeMillis()
        val event = KeyEvent(
            now, now, action, keyCode, repeat, metaState, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
            InputDevice.SOURCE_KEYBOARD
        )
        return injectEvent(event, displayId, injectMode)
    }

    fun pressReleaseKeycode(keyCode: Int, displayId: Int, injectMode: Int): Boolean {
        return injectKeyEvent(KeyEvent.ACTION_DOWN, keyCode, 0, 0, displayId, injectMode)
                && injectKeyEvent(KeyEvent.ACTION_UP, keyCode, 0, 0, displayId, injectMode)
    }

    fun isScreenOn(displayId: Int): Boolean {
        assert(displayId != DISPLAY_ID_NONE)
        return powerManager!!.isScreenOn(displayId)
    }

    fun expandNotificationPanel() {
        statusBarManager!!.expandNotificationsPanel()
    }

    fun expandSettingsPanel() {
        statusBarManager!!.expandSettingsPanel()
    }

    fun collapsePanels() {
        statusBarManager!!.collapsePanels()
    }

    val clipboardText: String?
        get() {
            val clipboardManager = clipboardManager
                ?: return null
            val s = clipboardManager.text ?: return null
            return s.toString()
        }

    fun setClipboardText(text: String): Boolean {
        val clipboardManager = clipboardManager
            ?: return false

        val currentClipboard = clipboardText
        if (currentClipboard != null && currentClipboard == text) {
            // The clipboard already contains the requested text.
            // Since pasting text from the computer involves setting the device clipboard, it could be set twice on a copy-paste. This would cause
            // the clipboard listeners to be notified twice, and that would flood the Android keyboard clipboard history. To workaround this
            // problem, do not explicitly set the clipboard text if it already contains the expected content.
            return false
        }

        return clipboardManager.setText(text)
    }

    fun setDisplayPower(displayId: Int, on: Boolean): Boolean {
        assert(displayId != DISPLAY_ID_NONE)

        if (USE_ANDROID_15_DISPLAY_POWER && Build.VERSION.SDK_INT >= AndroidVersions.API_35_ANDROID_15) {
            return displayManager!!.requestDisplayPower(displayId, on)
        }

        var applyToMultiPhysicalDisplays =
            Build.VERSION.SDK_INT >= AndroidVersions.API_29_ANDROID_10

        if (applyToMultiPhysicalDisplays
            && Build.VERSION.SDK_INT >= AndroidVersions.API_34_ANDROID_14 && Build.BRAND.equals(
                "honor",
                ignoreCase = true
            )
            && hasGetBuildInDisplayMethod()
        ) {
            // Workaround for Honor devices with Android 14:
            //  - <https://github.com/Genymobile/scrcpy/issues/4823>
            //  - <https://github.com/Genymobile/scrcpy/issues/4943>
            applyToMultiPhysicalDisplays = false
        }

        val mode = if (on) POWER_MODE_NORMAL else POWER_MODE_OFF
        if (applyToMultiPhysicalDisplays) {
            // On Android 14, these internal methods have been moved to DisplayControl
            val useDisplayControl =
                Build.VERSION.SDK_INT >= AndroidVersions.API_34_ANDROID_14 && !hasGetPhysicalDisplayIdsMethod()

            // Change the power mode for all physical displays
            val physicalDisplayIds =
                if (useDisplayControl) DisplayControl.physicalDisplayIds else SurfaceControl.physicalDisplayIds
            if (physicalDisplayIds == null) {
                Ln.e("Could not get physical display ids")
                return false
            }

            var allOk = true
            for (physicalDisplayId in physicalDisplayIds) {
                val binder = if (useDisplayControl) DisplayControl.getPhysicalDisplayToken(
                    physicalDisplayId
                ) else SurfaceControl.getPhysicalDisplayToken(physicalDisplayId)
                allOk = allOk and setDisplayPowerMode(binder, mode)
            }
            return allOk
        }

        // Older Android versions, only 1 display
        val d = builtInDisplay
        if (d == null) {
            Ln.e("Could not get built-in display")
            return false
        }
        return setDisplayPowerMode(d, mode)
    }

    fun powerOffScreen(displayId: Int): Boolean {
        assert(displayId != DISPLAY_ID_NONE)

        if (!isScreenOn(displayId)) {
            return true
        }
        return pressReleaseKeycode(KeyEvent.KEYCODE_POWER, displayId, INJECT_MODE_ASYNC)
    }

    /**
     * Disable auto-rotation (if enabled), set the screen rotation and re-enable auto-rotation (if it was enabled).
     */
    fun rotateDevice(displayId: Int) {
        assert(displayId != DISPLAY_ID_NONE)

        val wm = windowManager

        val accelerometerRotation = !wm!!.isRotationFrozen(displayId)

        val currentRotation = getCurrentRotation(displayId)
        val newRotation = (currentRotation and 1) xor 1 // 0->1, 1->0, 2->1, 3->0
        val newRotationString = if (newRotation == 0) "portrait" else "landscape"

        Ln.i("Device rotation requested: $newRotationString")
        wm.freezeRotation(displayId, newRotation)

        // restore auto-rotate if necessary
        if (accelerometerRotation) {
            wm.thawRotation(displayId)
        }
    }

    private fun getCurrentRotation(displayId: Int): Int {
        assert(displayId != DISPLAY_ID_NONE)

        if (displayId == 0) {
            return windowManager!!.rotation
        }

        val displayInfo = displayManager!!.getDisplayInfo(displayId)
        return displayInfo!!.rotation
    }

    fun listApps(): List<DeviceApp> {
        val apps: MutableList<DeviceApp> = ArrayList()
        val pm = get().packageManager
        for (appInfo in getLaunchableApps(pm)) {
            apps.add(toApp(pm, appInfo))
        }

        return apps
    }

    @SuppressLint("QueryPermissionsNeeded")
    private fun getLaunchableApps(pm: PackageManager): List<ApplicationInfo> {
        val result: MutableList<ApplicationInfo> = ArrayList()
        for (appInfo in pm.getInstalledApplications(PackageManager.GET_META_DATA)) {
            if (appInfo.enabled && getLaunchIntent(pm, appInfo.packageName) != null) {
                result.add(appInfo)
            }
        }

        return result
    }

    fun getLaunchIntent(pm: PackageManager, packageName: String): Intent? {
        val launchIntent = pm.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            return launchIntent
        }

        return pm.getLeanbackLaunchIntentForPackage(packageName)
    }

    private fun toApp(pm: PackageManager, appInfo: ApplicationInfo): DeviceApp {
        val name = pm.getApplicationLabel(appInfo).toString()
        val system = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        return DeviceApp(appInfo.packageName, name, system)
    }

    @SuppressLint("QueryPermissionsNeeded")
    fun findByPackageName(packageName: String): DeviceApp? {
        val pm = get().packageManager
        // No need to filter by "launchable" apps, an error will be reported on start if the app is not launchable
        for (appInfo in pm.getInstalledApplications(PackageManager.GET_META_DATA)) {
            if (packageName == appInfo.packageName) {
                return toApp(pm, appInfo)
            }
        }

        return null
    }

    @SuppressLint("QueryPermissionsNeeded")
    fun findByName(searchName: String): List<DeviceApp> {
        var searchName = searchName
        val result: MutableList<DeviceApp> = ArrayList()
        searchName = searchName.lowercase(Locale.getDefault())

        val pm = get().packageManager
        for (appInfo in getLaunchableApps(pm)) {
            val name = pm.getApplicationLabel(appInfo).toString()
            if (name.lowercase(Locale.getDefault()).startsWith(searchName)) {
                val system = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                result.add(DeviceApp(appInfo.packageName, name, system))
            }
        }

        return result
    }

    fun startApp(packageName: String, displayId: Int, forceStop: Boolean) {
        val pm = get().packageManager

        val launchIntent = getLaunchIntent(pm, packageName)
        if (launchIntent == null) {
            Ln.w("Cannot create launch intent for app $packageName")
            return
        }

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        var options: Bundle? = null
        if (Build.VERSION.SDK_INT >= AndroidVersions.API_26_ANDROID_8_0) {
            val launchOptions = ActivityOptions.makeBasic()
            launchOptions.setLaunchDisplayId(displayId)
            options = launchOptions.toBundle()
        }

        val am = activityManager
        if (forceStop) {
            am!!.forceStopPackage(packageName)
        }
        am!!.startActivity(launchIntent, options)
    }
}