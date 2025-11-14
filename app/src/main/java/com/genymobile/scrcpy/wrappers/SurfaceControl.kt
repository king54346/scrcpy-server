package com.genymobile.scrcpy.wrappers

import android.annotation.SuppressLint
import android.graphics.Rect
import android.os.Build
import android.os.IBinder
import android.view.Surface
import com.genymobile.scrcpy.AndroidVersions
import com.genymobile.scrcpy.util.Ln
import java.lang.reflect.Method

@SuppressLint("PrivateApi")
object SurfaceControl {
    private var CLASS: Class<*>? = null

    // see <https://android.googlesource.com/platform/frameworks/base.git/+/pie-release-2/core/java/android/view/SurfaceControl.java#305>
    const val POWER_MODE_OFF: Int = 0
    const val POWER_MODE_NORMAL: Int = 2

    init {
        try {
            CLASS = Class.forName("android.view.SurfaceControl")
        } catch (e: ClassNotFoundException) {
            throw AssertionError(e)
        }
    }

    @get:Throws(NoSuchMethodException::class)
    private var getBuiltInDisplayMethod: Method? = null
        get() {
            if (field == null) {
                // the method signature has changed in Android 10
                // <https://github.com/Genymobile/scrcpy/issues/586>
                field = if (Build.VERSION.SDK_INT < AndroidVersions.API_29_ANDROID_10) {
                    CLASS!!.getMethod(
                        "getBuiltInDisplay",
                        Int::class.javaPrimitiveType
                    )
                } else {
                    CLASS!!.getMethod("getInternalDisplayToken")
                }
            }
            return field
        }

    @get:Throws(NoSuchMethodException::class)
    private var setDisplayPowerModeMethod: Method? = null
        get() {
            if (field == null) {
                field = CLASS!!.getMethod(
                    "setDisplayPowerMode",
                    IBinder::class.java,
                    Int::class.javaPrimitiveType
                )
            }
            return field
        }

    @get:Throws(NoSuchMethodException::class)
    private var getPhysicalDisplayTokenMethod: Method? = null
        get() {
            if (field == null) {
                field = CLASS!!.getMethod(
                    "getPhysicalDisplayToken",
                    Long::class.javaPrimitiveType
                )
            }
            return field
        }

    @get:Throws(NoSuchMethodException::class)
    private var getPhysicalDisplayIdsMethod: Method? = null
        get() {
            if (field == null) {
                field = CLASS!!.getMethod("getPhysicalDisplayIds")
            }
            return field
        }

    fun openTransaction() {
        try {
            CLASS!!.getMethod("openTransaction").invoke(null)
        } catch (e: Exception) {
            throw AssertionError(e)
        }
    }

    fun closeTransaction() {
        try {
            CLASS!!.getMethod("closeTransaction").invoke(null)
        } catch (e: Exception) {
            throw AssertionError(e)
        }
    }

    fun setDisplayProjection(
        displayToken: IBinder?,
        orientation: Int,
        layerStackRect: Rect?,
        displayRect: Rect?
    ) {
        try {
            CLASS!!.getMethod(
                "setDisplayProjection",
                IBinder::class.java,
                Int::class.javaPrimitiveType,
                Rect::class.java,
                Rect::class.java
            )
                .invoke(null, displayToken, orientation, layerStackRect, displayRect)
        } catch (e: Exception) {
            throw AssertionError(e)
        }
    }

    fun setDisplayLayerStack(displayToken: IBinder?, layerStack: Int) {
        try {
            CLASS!!.getMethod(
                "setDisplayLayerStack",
                IBinder::class.java,
                Int::class.javaPrimitiveType
            ).invoke(null, displayToken, layerStack)
        } catch (e: Exception) {
            throw AssertionError(e)
        }
    }

    fun setDisplaySurface(displayToken: IBinder?, surface: Surface?) {
        try {
            CLASS!!.getMethod(
                "setDisplaySurface",
                IBinder::class.java,
                Surface::class.java
            ).invoke(null, displayToken, surface)
        } catch (e: Exception) {
            throw AssertionError(e)
        }
    }

    @Throws(Exception::class)
    fun createDisplay(name: String?, secure: Boolean): IBinder {
        return CLASS!!.getMethod(
            "createDisplay",
            String::class.java,
            Boolean::class.javaPrimitiveType
        ).invoke(null, name, secure) as IBinder
    }

    fun hasGetBuildInDisplayMethod(): Boolean {
        try {
            getBuiltInDisplayMethod
            return true
        } catch (e: NoSuchMethodException) {
            return false
        }
    }

    val builtInDisplay: IBinder?
        get() {
            try {
                val method =
                    getBuiltInDisplayMethod!!
                if (Build.VERSION.SDK_INT < AndroidVersions.API_29_ANDROID_10) {
                    // call getBuiltInDisplay(0)
                    return method.invoke(null, 0) as IBinder
                }

                // call getInternalDisplayToken()
                return method.invoke(null) as IBinder
            } catch (e: ReflectiveOperationException) {
                Ln.e("Could not invoke method", e)
                return null
            }
        }

    fun getPhysicalDisplayToken(physicalDisplayId: Long): IBinder? {
        try {
            val method =
                getPhysicalDisplayTokenMethod!!
            return method.invoke(null, physicalDisplayId) as IBinder
        } catch (e: ReflectiveOperationException) {
            Ln.e("Could not invoke method", e)
            return null
        }
    }

    fun hasGetPhysicalDisplayIdsMethod(): Boolean {
        try {
            getPhysicalDisplayIdsMethod
            return true
        } catch (e: NoSuchMethodException) {
            return false
        }
    }

    val physicalDisplayIds: LongArray?
        get() {
            try {
                val method =
                    getPhysicalDisplayIdsMethod!!
                return method.invoke(null) as LongArray
            } catch (e: ReflectiveOperationException) {
                Ln.e("Could not invoke method", e)
                return null
            }
        }

    fun setDisplayPowerMode(displayToken: IBinder?, mode: Int): Boolean {
        try {
            val method =
                setDisplayPowerModeMethod!!
            method.invoke(null, displayToken, mode)
            return true
        } catch (e: ReflectiveOperationException) {
            Ln.e("Could not invoke method", e)
            return false
        }
    }

    fun destroyDisplay(displayToken: IBinder?) {
        try {
            CLASS!!.getMethod("destroyDisplay", IBinder::class.java).invoke(null, displayToken)
        } catch (e: Exception) {
            throw AssertionError(e)
        }
    }
}