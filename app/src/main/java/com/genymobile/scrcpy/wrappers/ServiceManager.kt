package com.genymobile.scrcpy.wrappers

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraManager
import android.os.IBinder
import android.os.IInterface
import com.genymobile.scrcpy.FakeContext.Companion.get
import java.lang.reflect.Method

@SuppressLint("PrivateApi,DiscouragedPrivateApi")
object ServiceManager {
    private var GET_SERVICE_METHOD: Method? = null

    init {
        try {
            GET_SERVICE_METHOD = Class.forName("android.os.ServiceManager").getDeclaredMethod(
                "getService",
                String::class.java
            )
        } catch (e: Exception) {
            throw AssertionError(e)
        }
    }

    var windowManager: WindowManager? = null
        get() {
            if (field == null) {
                field = WindowManager.create()
            }
            return field
        }
        private set

    @get:Synchronized
    var displayManager: DisplayManager? = null
        // The DisplayManager may be used from both the Controller thread and the video (main) thread
        get() {
            if (field == null) {
                field = DisplayManager.create()
            }
            return field
        }
        private set
    var inputManager: InputManager? = null
        get() {
            if (field == null) {
                field = InputManager.create()
            }
            return field
        }
        private set
    var powerManager: PowerManager? = null
        get() {
            if (field == null) {
                field = PowerManager.create()
            }
            return field
        }
        private set
    var statusBarManager: StatusBarManager? = null
        get() {
            if (field == null) {
                field = StatusBarManager.create()
            }
            return field
        }
        private set
    var clipboardManager: ClipboardManager? = null
        get() {
            if (field == null) {
                // May be null, some devices have no clipboard manager
                field = ClipboardManager.create()
            }
            return field
        }
        private set
    var activityManager: ActivityManager? = null
        get() {
            if (field == null) {
                field = ActivityManager.create()
            }
            return field
        }
        private set
    var cameraManager: CameraManager? = null
        get() {
            if (field == null) {
                try {
                    val ctor =
                        CameraManager::class.java.getDeclaredConstructor(Context::class.java)
                    field = ctor.newInstance(get())
                } catch (e: Exception) {
                    throw AssertionError(e)
                }
            }
            return field
        }
        private set

    fun getService(service: String?, type: String): IInterface {
        try {
            val binder = GET_SERVICE_METHOD!!.invoke(null, service) as IBinder
            val asInterfaceMethod = Class.forName("$type\$Stub").getMethod(
                "asInterface",
                IBinder::class.java
            )
            return asInterfaceMethod.invoke(null, binder) as IInterface
        } catch (e: Exception) {
            throw AssertionError(e)
        }
    }
}