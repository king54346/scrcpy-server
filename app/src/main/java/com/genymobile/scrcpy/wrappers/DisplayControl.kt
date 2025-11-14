package com.genymobile.scrcpy.wrappers

import android.annotation.SuppressLint
import android.os.IBinder
import android.system.Os
import androidx.annotation.RequiresApi
import com.genymobile.scrcpy.AndroidVersions
import com.genymobile.scrcpy.util.Ln
import java.lang.reflect.Method

@SuppressLint("PrivateApi", "SoonBlockedPrivateApi", "BlockedPrivateApi")
@RequiresApi(AndroidVersions.API_34_ANDROID_14)
object DisplayControl {
    private val CLASS: Class<*>?

    init {
        var displayControlClass: Class<*>? = null
        try {
            val classLoaderFactoryClass =
                Class.forName("com.android.internal.os.ClassLoaderFactory")
            val createClassLoaderMethod = classLoaderFactoryClass.getDeclaredMethod(
                "createClassLoader",
                String::class.java,
                String::class.java,
                String::class.java,
                ClassLoader::class.java,
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                String::class.java
            )

            val systemServerClasspath = Os.getenv("SYSTEMSERVERCLASSPATH")
            val classLoader = createClassLoaderMethod.invoke(
                null, systemServerClasspath, null, null,
                ClassLoader.getSystemClassLoader(), 0, true, null
            ) as ClassLoader

            displayControlClass = classLoader.loadClass("com.android.server.display.DisplayControl")

            val loadMethod = Runtime::class.java.getDeclaredMethod(
                "loadLibrary0",
                Class::class.java,
                String::class.java
            )
            loadMethod.isAccessible = true
            loadMethod.invoke(Runtime.getRuntime(), displayControlClass, "android_servers")
        } catch (e: Throwable) {
            Ln.e("Could not initialize DisplayControl", e)
            // Do not throw an exception here, the methods will fail when they are called
        }
        CLASS = displayControlClass
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

    fun getPhysicalDisplayToken(physicalDisplayId: Long): IBinder? {
        try {
            val method = getPhysicalDisplayTokenMethod!!
            return method.invoke(null, physicalDisplayId) as IBinder
        } catch (e: ReflectiveOperationException) {
            Ln.e("Could not invoke method", e)
            return null
        }
    }

    val physicalDisplayIds: LongArray?
        get() {
            try {
                val method = getPhysicalDisplayIdsMethod!!
                return method.invoke(null) as LongArray
            } catch (e: ReflectiveOperationException) {
                Ln.e("Could not invoke method", e)
                return null
            }
        }
}