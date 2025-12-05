package com.genymobile.scrcpy.wrappers

import android.os.Build
import android.os.IInterface
import com.genymobile.scrcpy.AndroidVersions
import com.genymobile.scrcpy.util.Ln
import java.lang.reflect.Method

class PowerManager private constructor(private val manager: IInterface) {
    @get:Throws(NoSuchMethodException::class)
    private var isScreenOnMethod: Method? = null
        get() {
            if (field == null) {
                field = if (Build.VERSION.SDK_INT >= AndroidVersions.API_34_ANDROID_14) {
                    manager.javaClass.getMethod(
                        "isDisplayInteractive",
                        Int::class.javaPrimitiveType
                    )
                } else {
                    manager.javaClass.getMethod("isInteractive")
                }
            }
            return field
        }

    fun isScreenOn(displayId: Int): Boolean {
        try {
            val method = isScreenOnMethod!!
            if (Build.VERSION.SDK_INT >= AndroidVersions.API_34_ANDROID_14) {
                return method.invoke(manager, displayId) as Boolean
            }
            return method.invoke(manager) as Boolean
        } catch (e: ReflectiveOperationException) {
            Ln.e("Could not invoke method", e)
            return false
        }
    }

    companion object {
        fun create(): PowerManager {
            val manager: IInterface = ServiceManager.getService("power", "android.os.IPowerManager")
            return PowerManager(manager)
        }
    }
}