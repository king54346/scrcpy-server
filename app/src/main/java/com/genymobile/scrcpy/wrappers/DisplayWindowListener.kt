package com.genymobile.scrcpy.wrappers

import android.content.res.Configuration
import android.os.Parcel
import android.view.IDisplayWindowListener
import com.genymobile.scrcpy.util.Ln

open class DisplayWindowListener : IDisplayWindowListener.Stub() {
    //     当新显示设备添加时调用
    override fun onDisplayAdded(displayId: Int) {
        // empty default implementation
    }
//     当显示配置改变时调用(如屏幕旋转、分辨率变化)
    override fun onDisplayConfigurationChanged(displayId: Int, newConfig: Configuration) {
        // empty default implementation
    }
//    当显示设备移除时调用
    override fun onDisplayRemoved(displayId: Int) {
        // empty default implementation
    }
//    Binder 通信的核心方法
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        try {
            return super.onTransact(code, data, reply, flags)
        } catch (e: AbstractMethodError) {
            Ln.v("Ignoring AbstractMethodError: " + e.message)
            // Ignore unknown methods, write default response to reply parcel
            reply?.writeNoException()
            return true
        }
    }
}