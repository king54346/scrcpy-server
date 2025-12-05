package com.genymobile.scrcpy.wrappers

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.IContentProvider
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.IInterface
import com.genymobile.scrcpy.AndroidVersions
import com.genymobile.scrcpy.FakeContext
import com.genymobile.scrcpy.util.Ln
import java.lang.reflect.Method

@SuppressLint("PrivateApi,DiscouragedPrivateApi")
class ActivityManager private constructor(private val manager: IInterface) {
    @get:Throws(NoSuchMethodException::class)
    private var getContentProviderExternalMethod: Method? = null
        get() {
            if (field == null) {
                try {
                    field = manager.javaClass
                        .getMethod(
                            "getContentProviderExternal",
                            String::class.java,
                            Int::class.javaPrimitiveType,
                            IBinder::class.java,
                            String::class.java
                        )
                } catch (e: NoSuchMethodException) {
                    // old version
                    field = manager.javaClass.getMethod(
                        "getContentProviderExternal",
                        String::class.java,
                        Int::class.javaPrimitiveType,
                        IBinder::class.java
                    )
                    getContentProviderExternalMethodNewVersion = false
                }
            }
            return field
        }
    private var getContentProviderExternalMethodNewVersion = true

    @get:Throws(NoSuchMethodException::class)
    private var removeContentProviderExternalMethod: Method? = null
        get() {
            if (field == null) {
                field = manager.javaClass.getMethod(
                    "removeContentProviderExternal",
                    String::class.java,
                    IBinder::class.java
                )
            }
            return field
        }

    @get:Throws(
        NoSuchMethodException::class,
        ClassNotFoundException::class
    )
    private var startActivityAsUserMethod: Method? = null
        get() {
            if (field == null) {
                val iApplicationThreadClass = Class.forName("android.app.IApplicationThread")
                val profilerInfo = Class.forName("android.app.ProfilerInfo")
                field = manager.javaClass
                    .getMethod(
                        "startActivityAsUser", iApplicationThreadClass,
                        String::class.java,
                        Intent::class.java,
                        String::class.java,
                        IBinder::class.java,
                        String::class.java,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType, profilerInfo,
                        Bundle::class.java,
                        Int::class.javaPrimitiveType
                    )
            }
            return field
        }

    @get:Throws(NoSuchMethodException::class)
    private var forceStopPackageMethod: Method? = null
        get() {
            if (field == null) {
                field = manager.javaClass.getMethod(
                    "forceStopPackage",
                    String::class.java,
                    Int::class.javaPrimitiveType
                )
            }
            return field
        }

    @TargetApi(AndroidVersions.API_29_ANDROID_10)
    fun getContentProviderExternal(name: String?, token: IBinder?): IContentProvider? {
        try {
            val method = getContentProviderExternalMethod!!
            val args = if (getContentProviderExternalMethodNewVersion) {
                // new version
                arrayOf(name, FakeContext.ROOT_UID, token, null)
            } else {
                // old version
                arrayOf(name, FakeContext.ROOT_UID, token)
            }
            // ContentProviderHolder providerHolder = getContentProviderExternal(...);
            val providerHolder = method.invoke(manager, *args) ?: return null
            // IContentProvider provider = providerHolder.provider;
            val providerField = providerHolder.javaClass.getDeclaredField("provider")
            providerField.isAccessible = true
            return providerField[providerHolder] as IContentProvider
        } catch (e: ReflectiveOperationException) {
            Ln.e("Could not invoke method", e)
            return null
        }
    }

    fun removeContentProviderExternal(name: String?, token: IBinder?) {
        try {
            val method = removeContentProviderExternalMethod!!
            method.invoke(manager, name, token)
        } catch (e: ReflectiveOperationException) {
            Ln.e("Could not invoke method", e)
        }
    }

    fun createSettingsProvider(): ContentProvider? {
        val token: IBinder = Binder()
        val provider = getContentProviderExternal("settings", token) ?: return null
        return ContentProvider(this, provider, "settings", token)
    }

    @JvmOverloads
    fun startActivity(intent: Intent?, options: Bundle? = null): Int {
        try {
            val method = startActivityAsUserMethod!!
            return method.invoke( /* this */
                manager,  /* caller */
                null,  /* callingPackage */
                FakeContext.PACKAGE_NAME,  /* intent */
                intent,  /* resolvedType */
                null,  /* resultTo */
                null,  /* resultWho */
                null,  /* requestCode */
                0,  /* startFlags */
                0,  /* profilerInfo */
                null,  /* bOptions */
                options,  /* userId */ /* UserHandle.USER_CURRENT */
                -2
            ) as Int
        } catch (e: Throwable) {
            Ln.e("Could not invoke method", e)
            return 0
        }
    }

    fun forceStopPackage(packageName: String?) {
        try {
            val method = forceStopPackageMethod!!
            method.invoke(manager, packageName,  /* userId */ /* UserHandle.USER_CURRENT */-2)
        } catch (e: Throwable) {
            Ln.e("Could not invoke method", e)
        }
    }

    companion object {
        fun create(): ActivityManager {
            try {
                // On old Android versions, the ActivityManager is not exposed via AIDL,
                // so use ActivityManagerNative.getDefault()
                val cls = Class.forName("android.app.ActivityManagerNative")
                val getDefaultMethod = cls.getDeclaredMethod("getDefault")
                val am = getDefaultMethod.invoke(null) as IInterface
                return ActivityManager(am)
            } catch (e: ReflectiveOperationException) {
                throw AssertionError(e)
            }
        }
    }
}