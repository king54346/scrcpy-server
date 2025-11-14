package com.genymobile.scrcpy.wrappers

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.display.VirtualDisplay
import android.os.Handler
import android.view.Display
import android.view.Surface
import androidx.annotation.RequiresApi
import com.genymobile.scrcpy.AndroidVersions
import com.genymobile.scrcpy.FakeContext
import com.genymobile.scrcpy.FakeContext.Companion.get
import com.genymobile.scrcpy.device.DisplayInfo
import com.genymobile.scrcpy.device.Size
import com.genymobile.scrcpy.util.Command
import com.genymobile.scrcpy.util.Ln
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.regex.Pattern

@SuppressLint("PrivateApi,DiscouragedPrivateApi")
class DisplayManager private constructor(// instance of hidden class android.hardware.display.DisplayManagerGlobal
    private val manager: Any
) {
    fun interface DisplayListener {
        /**
         * Called whenever the properties of a logical [android.view.Display],
         * such as size and density, have changed.
         *
         * @param displayId The id of the logical display that changed.
         */
        fun onDisplayChanged(displayId: Int)
    }

    class DisplayListenerHandle internal constructor(val displayListenerProxy: Any)

    @get:Throws(NoSuchMethodException::class)
    @get:Synchronized
    private var getDisplayInfoMethod: Method? = null
        // getDisplayInfo() may be used from both the Controller thread and the video (main) thread
        get() {
            if (field == null) {
                field = manager.javaClass.getMethod("getDisplayInfo", Int::class.javaPrimitiveType)
            }
            return field
        }

    @get:Throws(NoSuchMethodException::class)
    private var createVirtualDisplayMethod: Method? = null
        get() {
            if (field == null) {
                field = android.hardware.display.DisplayManager::class.java
                    .getMethod(
                        "createVirtualDisplay",
                        String::class.java,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        Surface::class.java
                    )
            }
            return field
        }

    @get:Throws(NoSuchMethodException::class)
    private var requestDisplayPowerMethod: Method? = null
        get() {
            if (field == null) {
                field = manager.javaClass.getMethod(
                    "requestDisplayPower",
                    Int::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType
                )
            }
            return field
        }

    fun getDisplayInfo(displayId: Int): DisplayInfo? {
        try {
            val method = getDisplayInfoMethod!!
            val displayInfo = method.invoke(manager, displayId)
                ?: // fallback when displayInfo is null
                return getDisplayInfoFromDumpsysDisplay(displayId)
            val cls: Class<*> = displayInfo.javaClass
            // width and height already take the rotation into account
            val width = cls.getDeclaredField("logicalWidth").getInt(displayInfo)
            val height = cls.getDeclaredField("logicalHeight").getInt(displayInfo)
            val rotation = cls.getDeclaredField("rotation").getInt(displayInfo)
            val layerStack = cls.getDeclaredField("layerStack").getInt(displayInfo)
            val flags = cls.getDeclaredField("flags").getInt(displayInfo)
            val dpi = cls.getDeclaredField("logicalDensityDpi").getInt(displayInfo)
            val uniqueId = cls.getDeclaredField("uniqueId")[displayInfo] as String
            return DisplayInfo(
                displayId,
                Size(width, height),
                rotation,
                layerStack,
                flags,
                dpi,
                uniqueId
            )
        } catch (e: ReflectiveOperationException) {
            throw AssertionError(e)
        }
    }

    val displayIds: IntArray
        get() {
            try {
                return manager.javaClass.getMethod("getDisplayIds").invoke(manager) as IntArray
            } catch (e: ReflectiveOperationException) {
                throw AssertionError(e)
            }
        }

    @Throws(Exception::class)
    fun createVirtualDisplay(
        name: String?,
        width: Int,
        height: Int,
        displayIdToMirror: Int,
        surface: Surface?
    ): VirtualDisplay {
        val method = createVirtualDisplayMethod!!
        return method.invoke(
            null,
            name,
            width,
            height,
            displayIdToMirror,
            surface
        ) as VirtualDisplay
    }

    @Throws(Exception::class)
    fun createNewVirtualDisplay(
        name: String,
        width: Int,
        height: Int,
        dpi: Int,
        surface: Surface?,
        flags: Int
    ): VirtualDisplay {
        val ctor = android.hardware.display.DisplayManager::class.java.getDeclaredConstructor(
            Context::class.java
        )
        ctor.isAccessible = true
        val dm = ctor.newInstance(get())
        return dm.createVirtualDisplay(name, width, height, dpi, surface, flags)
    }

    @RequiresApi(AndroidVersions.API_35_ANDROID_15)
    fun requestDisplayPower(displayId: Int, on: Boolean): Boolean {
        try {
            val method = requestDisplayPowerMethod!!
            return method.invoke(manager, displayId, on) as Boolean
        } catch (e: ReflectiveOperationException) {
            Ln.e("Could not invoke method", e)
            return false
        }
    }

    fun registerDisplayListener(
        listener: DisplayListener,
        handler: Handler?
    ): DisplayListenerHandle? {
        try {
            val displayListenerClass =
                Class.forName("android.hardware.display.DisplayManager\$DisplayListener")
            val displayListenerProxy = Proxy.newProxyInstance(
                ClassLoader.getSystemClassLoader(),
                arrayOf(displayListenerClass)
            ) { proxy: Any?, method: Method, args: Array<Any> ->
                if ("onDisplayChanged" == method.name) {
                    listener.onDisplayChanged(args[0] as Int)
                }
                if ("toString" == method.name) {
                    return@newProxyInstance "DisplayListener"
                }
                null
            }
            try {
                manager.javaClass
                    .getMethod(
                        "registerDisplayListener", displayListenerClass,
                        Handler::class.java,
                        Long::class.javaPrimitiveType,
                        String::class.java
                    )
                    .invoke(
                        manager,
                        displayListenerProxy,
                        handler,
                        EVENT_FLAG_DISPLAY_CHANGED,
                        FakeContext.PACKAGE_NAME
                    )
            } catch (e: NoSuchMethodException) {
                try {
                    manager.javaClass
                        .getMethod(
                            "registerDisplayListener", displayListenerClass,
                            Handler::class.java,
                            Long::class.javaPrimitiveType
                        )
                        .invoke(manager, displayListenerProxy, handler, EVENT_FLAG_DISPLAY_CHANGED)
                } catch (e2: NoSuchMethodException) {
                    manager.javaClass
                        .getMethod(
                            "registerDisplayListener",
                            displayListenerClass,
                            Handler::class.java
                        )
                        .invoke(manager, displayListenerProxy, handler)
                }
            }

            return DisplayListenerHandle(displayListenerProxy)
        } catch (e: Exception) {
            // Rotation and screen size won't be updated, not a fatal error
            Ln.e("Could not register display listener", e)
        }

        return null
    }

    fun unregisterDisplayListener(listener: DisplayListenerHandle) {
        try {
            val displayListenerClass =
                Class.forName("android.hardware.display.DisplayManager\$DisplayListener")
            manager.javaClass.getMethod("unregisterDisplayListener", displayListenerClass)
                .invoke(manager, listener.displayListenerProxy)
        } catch (e: Exception) {
            Ln.e("Could not unregister display listener", e)
        }
    }

    companion object {
        // android.hardware.display.DisplayManager.EVENT_FLAG_DISPLAY_CHANGED
        const val EVENT_FLAG_DISPLAY_CHANGED: Long = 1L shl 2

        fun create(): DisplayManager {
            try {
                val clazz = Class.forName("android.hardware.display.DisplayManagerGlobal")
                val getInstanceMethod = clazz.getDeclaredMethod("getInstance")
                val dmg = getInstanceMethod.invoke(null)
                return DisplayManager(dmg)
            } catch (e: ReflectiveOperationException) {
                throw AssertionError(e)
            }
        }

        // public to call it from unit tests
        fun parseDisplayInfo(dumpsysDisplayOutput: String, displayId: Int): DisplayInfo? {
            val regex = Pattern.compile(
                ("^    mOverrideDisplayInfo=DisplayInfo\\{\".*?, displayId " + displayId + ".*?(, FLAG_.*)?, real ([0-9]+) x ([0-9]+).*?, "
                        + "rotation ([0-9]+).*?, density ([0-9]+).*?, layerStack ([0-9]+)"),
                Pattern.MULTILINE
            )
            val m = regex.matcher(dumpsysDisplayOutput)
            if (!m.find()) {
                return null
            }
            val flags = parseDisplayFlags(m.group(1))
            val width = m.group(2).toInt()
            val height = m.group(3).toInt()
            val rotation = m.group(4).toInt()
            val density = m.group(5).toInt()
            val layerStack = m.group(6).toInt()

            return DisplayInfo(
                displayId,
                Size(width, height),
                rotation,
                layerStack,
                flags,
                density,
                null
            )
        }

        private fun getDisplayInfoFromDumpsysDisplay(displayId: Int): DisplayInfo? {
            try {
                val dumpsysDisplayOutput = Command.execReadOutput("dumpsys", "display")
                return parseDisplayInfo(dumpsysDisplayOutput, displayId)
            } catch (e: Exception) {
                Ln.e("Could not get display info from \"dumpsys display\" output", e)
                return null
            }
        }

        private fun parseDisplayFlags(text: String?): Int {
            if (text == null) {
                return 0
            }

            var flags = 0
            val regex = Pattern.compile("FLAG_[A-Z_]+")
            val m = regex.matcher(text)
            while (m.find()) {
                val flagString = m.group()
                try {
                    val filed = Display::class.java.getDeclaredField(flagString)
                    flags = flags or filed.getInt(null)
                } catch (e: ReflectiveOperationException) {
                    // Silently ignore, some flags reported by "dumpsys display" are @TestApi
                }
            }
            return flags
        }
    }
}