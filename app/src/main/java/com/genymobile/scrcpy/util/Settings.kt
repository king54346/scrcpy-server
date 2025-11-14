package com.genymobile.scrcpy.util

import com.genymobile.scrcpy.wrappers.ContentProvider
import com.genymobile.scrcpy.wrappers.ServiceManager.activityManager

object Settings {
    const val TABLE_SYSTEM: String = ContentProvider.TABLE_SYSTEM
    const val TABLE_SECURE: String = ContentProvider.TABLE_SECURE
    const val TABLE_GLOBAL: String = ContentProvider.TABLE_GLOBAL

    @Throws(SettingsException::class)
    fun getValue(table: String, key: String): String? {
        activityManager!!.createSettingsProvider().use { provider ->
            return provider!!.getValue(table, key)
        }
    }

    @Throws(SettingsException::class)
    fun putValue(table: String, key: String, value: String?) {
        activityManager!!.createSettingsProvider().use { provider ->
            provider!!.putValue(table, key, value)
        }
    }

    @Throws(SettingsException::class)
    fun getAndPutValue(table: String, key: String, value: String): String? {
        activityManager!!.createSettingsProvider().use { provider ->
            val oldValue = provider!!.getValue(table, key)
            if (value != oldValue) {
                provider.putValue(table, key, value)
            }
            return oldValue
        }
    }
}