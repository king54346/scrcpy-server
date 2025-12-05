package com.genymobile.scrcpy.device

class NewDisplay {
    var size: Size? = null
        private set
    var dpi: Int = 0
        private set

    constructor()

    constructor(size: Size?, dpi: Int) {
        this.size = size
        this.dpi = dpi
    }

    fun hasExplicitSize(): Boolean {
        return size != null
    }

    fun hasExplicitDpi(): Boolean {
        return dpi != 0
    }
}