package com.genymobile.scrcpy.wrappers

import android.annotation.SuppressLint
import android.content.AttributionSource
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import com.genymobile.scrcpy.AndroidVersions
import com.genymobile.scrcpy.FakeContext
import com.genymobile.scrcpy.FakeContext.Companion.get
import com.genymobile.scrcpy.util.Ln
import com.genymobile.scrcpy.util.SettingsException
import java.io.Closeable
import java.lang.reflect.Method

class ContentProvider internal constructor(
    private val manager: ActivityManager, // android.content.IContentProvider
    private val provider: Any, private val name: String, private val token: IBinder
) :
    Closeable {
    @get:Throws(NoSuchMethodException::class)
    @get:SuppressLint("PrivateApi")
    private var callMethod: Method? = null
        get() {
            if (field == null) {
                if (Build.VERSION.SDK_INT >= AndroidVersions.API_31_ANDROID_12) {
                    field = provider.javaClass.getMethod(
                        "call",
                        AttributionSource::class.java,
                        String::class.java,
                        String::class.java,
                        String::class.java,
                        Bundle::class.java
                    )
                    callMethodVersion = 0
                } else {
                    // old versions
                    try {
                        field = provider.javaClass
                            .getMethod(
                                "call",
                                String::class.java,
                                String::class.java,
                                String::class.java,
                                String::class.java,
                                String::class.java,
                                Bundle::class.java
                            )
                        callMethodVersion = 1
                    } catch (e1: NoSuchMethodException) {
                        try {
                            field = provider.javaClass.getMethod(
                                "call",
                                String::class.java,
                                String::class.java,
                                String::class.java,
                                String::class.java,
                                Bundle::class.java
                            )
                            callMethodVersion = 2
                        } catch (e2: NoSuchMethodException) {
                            field = provider.javaClass.getMethod(
                                "call",
                                String::class.java,
                                String::class.java,
                                String::class.java,
                                Bundle::class.java
                            )
                            callMethodVersion = 3
                        }
                    }
                }
            }
            return field
        }
    private var callMethodVersion = 0

    @Throws(ReflectiveOperationException::class)
    private fun call(callMethod: String, arg: String, extras: Bundle): Bundle {
        try {
            val method = this.callMethod!!

            val args =
                if (Build.VERSION.SDK_INT >= AndroidVersions.API_31_ANDROID_12 && callMethodVersion == 0) {
                    arrayOf<Any?>(get().attributionSource, "settings", callMethod, arg, extras)
                } else {
                    when (callMethodVersion) {
                        1 -> arrayOf(
                            FakeContext.PACKAGE_NAME,
                            null,
                            "settings",
                            callMethod,
                            arg,
                            extras
                        )

                        2 -> arrayOf<Any?>(
                            FakeContext.PACKAGE_NAME,
                            "settings",
                            callMethod,
                            arg,
                            extras
                        )

                        else -> arrayOf<Any?>(FakeContext.PACKAGE_NAME, callMethod, arg, extras)
                    }
                }
            return method.invoke(provider, *args) as Bundle
        } catch (e: ReflectiveOperationException) {
            Ln.e("Could not invoke method", e)
            throw e
        }
    }

    override fun close() {
        manager.removeContentProviderExternal(name, token)
    }

    @Throws(SettingsException::class)
    fun getValue(table: String, key: String): String? {
        val method = getGetMethod(table)
        val arg = Bundle()
        arg.putInt(CALL_METHOD_USER_KEY, FakeContext.ROOT_UID)
        try {
            val bundle = call(method, key, arg) ?: return null
            return bundle.getString("value")
        } catch (e: Exception) {
            throw SettingsException(table, "get", key, null, e)
        }
    }

    @Throws(SettingsException::class)
    fun putValue(table: String, key: String, value: String?) {
        val method = getPutMethod(table)
        val arg = Bundle()
        arg.putInt(CALL_METHOD_USER_KEY, FakeContext.ROOT_UID)
        arg.putString(NAME_VALUE_TABLE_VALUE, value)
        try {
            call(method, key, arg)
        } catch (e: Exception) {
            throw SettingsException(table, "put", key, value, e)
        }
    }

    companion object {
        const val TABLE_SYSTEM: String = "system"
        const val TABLE_SECURE: String = "secure"
        const val TABLE_GLOBAL: String = "global"

        // See android/providerHolder/Settings.java
        private const val CALL_METHOD_GET_SYSTEM = "GET_system"
        private const val CALL_METHOD_GET_SECURE = "GET_secure"
        private const val CALL_METHOD_GET_GLOBAL = "GET_global"

        private const val CALL_METHOD_PUT_SYSTEM = "PUT_system"
        private const val CALL_METHOD_PUT_SECURE = "PUT_secure"
        private const val CALL_METHOD_PUT_GLOBAL = "PUT_global"

        private const val CALL_METHOD_USER_KEY = "_user"

        private const val NAME_VALUE_TABLE_VALUE = "value"

        private fun getGetMethod(table: String): String {
            return when (table) {
                TABLE_SECURE -> CALL_METHOD_GET_SECURE
                TABLE_SYSTEM -> CALL_METHOD_GET_SYSTEM
                TABLE_GLOBAL -> CALL_METHOD_GET_GLOBAL
                else -> throw IllegalArgumentException("Invalid table: $table")
            }
        }

        private fun getPutMethod(table: String): String {
            return when (table) {
                TABLE_SECURE -> CALL_METHOD_PUT_SECURE
                TABLE_SYSTEM -> CALL_METHOD_PUT_SYSTEM
                TABLE_GLOBAL -> CALL_METHOD_PUT_GLOBAL
                else -> throw IllegalArgumentException("Invalid table: $table")
            }
        }
    }
}