package com.genymobile.scrcpy.wrappers

import android.os.Build
import android.os.IInterface
import android.view.IDisplayWindowListener
import androidx.annotation.RequiresApi
import com.genymobile.scrcpy.AndroidVersions
import com.genymobile.scrcpy.util.Ln
import com.genymobile.scrcpy.wrappers.ServiceManager.getService
import java.lang.reflect.Method

class WindowManager private constructor(private val manager: IInterface) {
    @get:Throws(NoSuchMethodException::class)
    private var getRotationMethod: Method? = null
        get() {
            if (field == null) {
                val cls: Class<*> = manager.javaClass
                field = try {
                    // method changed since this commit:
                    // https://android.googlesource.com/platform/frameworks/base/+/8ee7285128c3843401d4c4d0412cd66e86ba49e3%5E%21/#F2
                    cls.getMethod("getDefaultDisplayRotation")
                } catch (e: NoSuchMethodException) {
                    // old version
                    cls.getMethod("getRotation")
                }
            }
            return field
        }

    @get:Throws(NoSuchMethodException::class)
    private var freezeDisplayRotationMethod: Method? = null
        get() {
            if (field == null) {
                try {
                    // Android 15 preview and 14 QPR3 Beta added a String caller parameter for debugging:
                    // <https://android.googlesource.com/platform/frameworks/base/+/670fb7f5c0d23cf51ead25538bcb017e03ed73ac%5E%21/>
                    field = manager.javaClass.getMethod(
                        "freezeDisplayRotation",
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        String::class.java
                    )
                    freezeDisplayRotationMethodVersion = 0
                } catch (e: NoSuchMethodException) {
                    try {
                        // New method added by this commit:
                        // <https://android.googlesource.com/platform/frameworks/base/+/90c9005e687aa0f63f1ac391adc1e8878ab31759%5E%21/>
                        field = manager.javaClass.getMethod(
                            "freezeDisplayRotation",
                            Int::class.javaPrimitiveType,
                            Int::class.javaPrimitiveType
                        )
                        freezeDisplayRotationMethodVersion = 1
                    } catch (e1: NoSuchMethodException) {
                        field = manager.javaClass.getMethod(
                            "freezeRotation",
                            Int::class.javaPrimitiveType
                        )
                        freezeDisplayRotationMethodVersion = 2
                    }
                }
            }
            return field
        }
    private var freezeDisplayRotationMethodVersion = 0

    @get:Throws(NoSuchMethodException::class)
    private var isDisplayRotationFrozenMethod: Method? = null
        get() {
            if (field == null) {
                try {
                    // New method added by this commit:
                    // <https://android.googlesource.com/platform/frameworks/base/+/90c9005e687aa0f63f1ac391adc1e8878ab31759%5E%21/>
                    field = manager.javaClass.getMethod(
                        "isDisplayRotationFrozen",
                        Int::class.javaPrimitiveType
                    )
                    isDisplayRotationFrozenMethodVersion = 0
                } catch (e: NoSuchMethodException) {
                    field = manager.javaClass.getMethod("isRotationFrozen")
                    isDisplayRotationFrozenMethodVersion = 1
                }
            }
            return field
        }
    private var isDisplayRotationFrozenMethodVersion = 0

    @get:Throws(NoSuchMethodException::class)
    private var thawDisplayRotationMethod: Method? = null
        get() {
            if (field == null) {
                try {
                    // Android 15 preview and 14 QPR3 Beta added a String caller parameter for debugging:
                    // <https://android.googlesource.com/platform/frameworks/base/+/670fb7f5c0d23cf51ead25538bcb017e03ed73ac%5E%21/>
                    field = manager.javaClass.getMethod(
                        "thawDisplayRotation",
                        Int::class.javaPrimitiveType,
                        String::class.java
                    )
                    thawDisplayRotationMethodVersion = 0
                } catch (e: NoSuchMethodException) {
                    try {
                        // New method added by this commit:
                        // <https://android.googlesource.com/platform/frameworks/base/+/90c9005e687aa0f63f1ac391adc1e8878ab31759%5E%21/>
                        field = manager.javaClass.getMethod(
                            "thawDisplayRotation",
                            Int::class.javaPrimitiveType
                        )
                        thawDisplayRotationMethodVersion = 1
                    } catch (e1: NoSuchMethodException) {
                        field = manager.javaClass.getMethod("thawRotation")
                        thawDisplayRotationMethodVersion = 2
                    }
                }
            }
            return field
        }
    private var thawDisplayRotationMethodVersion = 0

    @get:Throws(NoSuchMethodException::class)
    @get:RequiresApi(AndroidVersions.API_29_ANDROID_10)
    private var getDisplayImePolicyMethod: Method? = null
        get() {
            if (field == null) {
                field = if (Build.VERSION.SDK_INT >= AndroidVersions.API_31_ANDROID_12) {
                    manager.javaClass.getMethod(
                        "getDisplayImePolicy",
                        Int::class.javaPrimitiveType
                    )
                } else {
                    manager.javaClass.getMethod("shouldShowIme", Int::class.javaPrimitiveType)
                }
            }
            return field
        }

    @get:Throws(NoSuchMethodException::class)
    @get:RequiresApi(AndroidVersions.API_29_ANDROID_10)
    private var setDisplayImePolicyMethod: Method? = null
        get() {
            if (field == null) {
                field = if (Build.VERSION.SDK_INT >= AndroidVersions.API_31_ANDROID_12) {
                    manager.javaClass.getMethod(
                        "setDisplayImePolicy",
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType
                    )
                } else {
                    manager.javaClass.getMethod(
                        "setShouldShowIme",
                        Int::class.javaPrimitiveType,
                        Boolean::class.javaPrimitiveType
                    )
                }
            }
            return field
        }

    val rotation: Int
        get() {
            try {
                val method = getRotationMethod!!
                return method.invoke(manager) as Int
            } catch (e: ReflectiveOperationException) {
                Ln.e("Could not invoke method", e)
                return 0
            }
        }

    fun freezeRotation(displayId: Int, rotation: Int) {
        try {
            val method = freezeDisplayRotationMethod!!
            when (freezeDisplayRotationMethodVersion) {
                0 -> method.invoke(manager, displayId, rotation, "scrcpy#freezeRotation")
                1 -> method.invoke(manager, displayId, rotation)
                else -> {
                    if (displayId != 0) {
                        Ln.e("Secondary display rotation not supported on this device")
                        return
                    }
                    method.invoke(manager, rotation)
                }
            }
        } catch (e: ReflectiveOperationException) {
            Ln.e("Could not invoke method", e)
        }
    }

    fun isRotationFrozen(displayId: Int): Boolean {
        try {
            val method = isDisplayRotationFrozenMethod!!
            when (isDisplayRotationFrozenMethodVersion) {
                0 -> return method.invoke(manager, displayId) as Boolean
                else -> {
                    if (displayId != 0) {
                        Ln.e("Secondary display rotation not supported on this device")
                        return false
                    }
                    return method.invoke(manager) as Boolean
                }
            }
        } catch (e: ReflectiveOperationException) {
            Ln.e("Could not invoke method", e)
            return false
        }
    }

    fun thawRotation(displayId: Int) {
        try {
            val method = thawDisplayRotationMethod!!
            when (thawDisplayRotationMethodVersion) {
                0 -> method.invoke(manager, displayId, "scrcpy#thawRotation")
                1 -> method.invoke(manager, displayId)
                else -> {
                    if (displayId != 0) {
                        Ln.e("Secondary display rotation not supported on this device")
                        return
                    }
                    method.invoke(manager)
                }
            }
        } catch (e: ReflectiveOperationException) {
            Ln.e("Could not invoke method", e)
        }
    }
    // 注册到系统(通常是系统服务调用)，系统会持有 Binder 对象,并在显示设备变化时回调onDisplayAdded事件
    @RequiresApi(AndroidVersions.API_30_ANDROID_11)
    fun registerDisplayWindowListener(listener: IDisplayWindowListener?): IntArray? {
        try {
            return manager.javaClass.getMethod(
                "registerDisplayWindowListener",
                IDisplayWindowListener::class.java
            ).invoke(manager, listener) as IntArray
        } catch (e: Exception) {
            Ln.e("Could not register display window listener", e)
        }
        return null
    }

    @RequiresApi(AndroidVersions.API_30_ANDROID_11)
    fun unregisterDisplayWindowListener(listener: IDisplayWindowListener?) {
        try {
            manager.javaClass.getMethod(
                "unregisterDisplayWindowListener",
                IDisplayWindowListener::class.java
            ).invoke(manager, listener)
        } catch (e: Exception) {
            Ln.e("Could not unregister display window listener", e)
        }
    }

    @RequiresApi(AndroidVersions.API_29_ANDROID_10)
    fun getDisplayImePolicy(displayId: Int): Int {
        try {
            val method = getDisplayImePolicyMethod!!
            if (Build.VERSION.SDK_INT >= AndroidVersions.API_31_ANDROID_12) {
                return method.invoke(manager, displayId) as Int
            }
            val shouldShowIme = method.invoke(manager, displayId) as Boolean
            return if (shouldShowIme) DISPLAY_IME_POLICY_LOCAL else DISPLAY_IME_POLICY_FALLBACK_DISPLAY
        } catch (e: ReflectiveOperationException) {
            Ln.e("Could not invoke method", e)
            return -1
        }
    }

    @RequiresApi(AndroidVersions.API_29_ANDROID_10)
    fun setDisplayImePolicy(displayId: Int, displayImePolicy: Int) {
        try {
            val method = setDisplayImePolicyMethod!!
            if (Build.VERSION.SDK_INT >= AndroidVersions.API_31_ANDROID_12) {
                method.invoke(manager, displayId, displayImePolicy)
            } else if (displayImePolicy != DISPLAY_IME_POLICY_HIDE) {
                method.invoke(manager, displayId, displayImePolicy == DISPLAY_IME_POLICY_LOCAL)
            } else {
                Ln.w("DISPLAY_IME_POLICY_HIDE is not supported before Android 12")
            }
        } catch (e: ReflectiveOperationException) {
            Ln.e("Could not invoke method", e)
        }
    }

    companion object {
        // <https://android.googlesource.com/platform/frameworks/base.git/+/2103ff441c66772c80c8560e322dcd9a45be7dcd/core/java/android/view/WindowManager.java#692>
        const val DISPLAY_IME_POLICY_LOCAL: Int = 0
        const val DISPLAY_IME_POLICY_FALLBACK_DISPLAY: Int = 1
        const val DISPLAY_IME_POLICY_HIDE: Int = 2

        fun create(): WindowManager {
            val manager = getService("window", "android.view.IWindowManager")
            return WindowManager(manager)
        }
    }
}