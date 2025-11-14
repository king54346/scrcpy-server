package com.genymobile.scrcpy.device

class DisplayInfo(
    val displayId: Int,
    val size: Size,
    val rotation: Int,
    val layerStack: Int,
    val flags: Int,
    val dpi: Int,
    val uniqueId: String?
) {
    companion object {
        const val FLAG_SUPPORTS_PROTECTED_BUFFERS: Int = 0x00000001
    }
}