package com.genymobile.scrcpy.wrappers

import android.os.IInterface
import com.genymobile.scrcpy.util.Ln
import com.genymobile.scrcpy.wrappers.ServiceManager.getService
import java.lang.reflect.Method

class StatusBarManager private constructor(private val manager: IInterface) {
    @get:Throws(NoSuchMethodException::class)
    private var expandNotificationsPanelMethod: Method? = null
        get() {
            if (field == null) {
                try {
                    field = manager.javaClass.getMethod("expandNotificationsPanel")
                } catch (e: NoSuchMethodException) {
                    // Custom version for custom vendor ROM: <https://github.com/Genymobile/scrcpy/issues/2551>
                    field = manager.javaClass.getMethod(
                        "expandNotificationsPanel",
                        Int::class.javaPrimitiveType
                    )
                    expandNotificationPanelMethodCustomVersion = true
                }
            }
            return field
        }
    private var expandNotificationPanelMethodCustomVersion = false
    private var expandSettingsPanelMethod: Method? = null
    private var expandSettingsPanelMethodNewVersion = true

    @get:Throws(NoSuchMethodException::class)
    private var collapsePanelsMethod: Method? = null
        get() {
            if (field == null) {
                field = manager.javaClass.getMethod("collapsePanels")
            }
            return field
        }

    @get:Throws(NoSuchMethodException::class)
    private val expandSettingsPanel: Method
        get() {
            if (expandSettingsPanelMethod == null) {
                try {
                    // Since Android 7: https://android.googlesource.com/platform/frameworks/base.git/+/a9927325eda025504d59bb6594fee8e240d95b01%5E%21/
                    expandSettingsPanelMethod = manager.javaClass.getMethod(
                        "expandSettingsPanel",
                        String::class.java
                    )
                } catch (e: NoSuchMethodException) {
                    // old version
                    expandSettingsPanelMethod = manager.javaClass.getMethod("expandSettingsPanel")
                    expandSettingsPanelMethodNewVersion = false
                }
            }
            return expandSettingsPanelMethod!!
        }

    fun expandNotificationsPanel() {
        try {
            val method = expandNotificationsPanelMethod!!
            if (expandNotificationPanelMethodCustomVersion) {
                method.invoke(manager, 0)
            } else {
                method.invoke(manager)
            }
        } catch (e: ReflectiveOperationException) {
            Ln.e("Could not invoke method", e)
        }
    }

    fun expandSettingsPanel() {
        try {
            val method = expandSettingsPanel
            if (expandSettingsPanelMethodNewVersion) {
                // new version
                method.invoke(manager, null as Any?)
            } else {
                // old version
                method.invoke(manager)
            }
        } catch (e: ReflectiveOperationException) {
            Ln.e("Could not invoke method", e)
        }
    }

    fun collapsePanels() {
        try {
            val method = collapsePanelsMethod!!
            method.invoke(manager)
        } catch (e: ReflectiveOperationException) {
            Ln.e("Could not invoke method", e)
        }
    }

    companion object {
        fun create(): StatusBarManager {
            val manager =
                getService("statusbar", "com.android.internal.statusbar.IStatusBarService")
            return StatusBarManager(manager)
        }
    }
}