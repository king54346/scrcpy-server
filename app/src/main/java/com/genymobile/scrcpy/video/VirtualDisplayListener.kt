package com.genymobile.scrcpy.video

import com.genymobile.scrcpy.control.PositionMapper

interface VirtualDisplayListener {
    fun onNewVirtualDisplay(displayId: Int, positionMapper: PositionMapper?)
}