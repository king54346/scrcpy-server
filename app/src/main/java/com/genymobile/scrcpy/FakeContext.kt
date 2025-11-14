package com.genymobile.scrcpy

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.AttributionSource
import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.content.IContentProvider
import android.os.Binder
import android.os.Process
import com.genymobile.scrcpy.wrappers.ServiceManager

// 它提供了访问系统服务的能力

class FakeContext private constructor() : ContextWrapper(Workarounds.systemContext) {

    private val contentResolver: ContentResolver = object : ContentResolver(this) {
        @Suppress("unused", "ProtectedInFinal")
        // @Override (but super-class method not visible)
        fun acquireProvider(c: Context, name: String): IContentProvider? {
           return ServiceManager.activityManager?.getContentProviderExternal(name, Binder())
        }

        @Suppress("unused")
        // @Override (but super-class method not visible)
        fun releaseProvider(icp: IContentProvider): Boolean {
            return false
        }

        @Suppress("unused", "ProtectedInFinal")
        // @Override (but super-class method not visible)
        fun acquireUnstableProvider(c: Context, name: String): IContentProvider? {
            return null
        }

        @Suppress("unused")
        // @Override (but super-class method not visible)
        fun releaseUnstableProvider(icp: IContentProvider): Boolean {
            return false
        }

        @Suppress("unused")
        // @Override (but super-class method not visible)
        fun unstableProviderDied(icp: IContentProvider) {
            // ignore
        }
    }

    override fun getPackageName(): String {
        return PACKAGE_NAME
    }

    override fun getOpPackageName(): String {
        return PACKAGE_NAME
    }

    @TargetApi(AndroidVersions.API_31_ANDROID_12)
    override fun getAttributionSource(): AttributionSource {
        val builder = AttributionSource.Builder(Process.SHELL_UID)
        builder.setPackageName(PACKAGE_NAME)
        return builder.build()
    }

    // @Override to be added on SDK upgrade for Android 14
    @Suppress("unused")
    override fun getDeviceId(): Int {
        return 0
    }

    override fun getApplicationContext(): Context {
        return this
    }

    override fun createPackageContext(packageName: String, flags: Int): Context {
        return this
    }

    override fun getContentResolver(): ContentResolver {
        return contentResolver
    }

    @SuppressLint("SoonBlockedPrivateApi")
    override fun getSystemService(name: String): Any? {
        val service = super.getSystemService(name) ?: return null

        // "semclipboard" is a Samsung-internal service
        // See <https://github.com/Genymobile/scrcpy/issues/6224>
        if (CLIPBOARD_SERVICE == name || "semclipboard" == name) {
            try {
                val field = service.javaClass.getDeclaredField("mContext")
                field.isAccessible = true
                field.set(service, this)
            } catch (e: ReflectiveOperationException) {
                throw RuntimeException(e)
            }
        }

        return service
    }

    companion object {
        const val PACKAGE_NAME = "com.android.shell"
        const val ROOT_UID = 0 // Like android.os.Process.ROOT_UID, but before API 29
        const val INPUT_SERVICE = Context.INPUT_SERVICE
        private val INSTANCE = FakeContext()

        @JvmStatic
        fun get(): FakeContext {
            return INSTANCE
        }
    }
}