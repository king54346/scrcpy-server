package com.genymobile.scrcpy.wrappers

import android.content.ClipData
import android.content.ClipboardManager.OnPrimaryClipChangedListener
import android.content.Context
import com.genymobile.scrcpy.FakeContext.Companion.get

class ClipboardManager private constructor(private val manager: android.content.ClipboardManager) {
    val text: CharSequence?
        get() {
            val clipData = manager.primaryClip
            if (clipData == null || clipData.itemCount == 0) {
                return null
            }
            return clipData.getItemAt(0).text
        }

    fun setText(text: CharSequence?): Boolean {
        val clipData = ClipData.newPlainText(null, text)
        manager.setPrimaryClip(clipData)
        return true
    }

    fun addPrimaryClipChangedListener(listener: OnPrimaryClipChangedListener?) {
        manager.addPrimaryClipChangedListener(listener)
    }

    companion object {
        fun create(): ClipboardManager? {
            //
            val manager =
                get().getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager?
                    ?: // Some devices have no clipboard manager
                    // <https://github.com/Genymobile/scrcpy/issues/1440>
                    // <https://github.com/Genymobile/scrcpy/issues/1556>
                    return null
            return ClipboardManager(manager)
        }
    }
}