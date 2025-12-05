package com.genymobile.scrcpy.util

object Binary {
    fun toUnsigned(value: Short): Int {
        return value.toInt() and 0xffff
    }

    fun toUnsigned(value: Byte): Int {
        return value.toInt() and 0xff
    }

    /**
     * Convert unsigned 16-bit fixed-point to a float between 0 and 1
     *
     * @param value encoded value
     * @return Float value between 0 and 1
     */
    fun u16FixedPointToFloat(value: Short): Float {
        val unsignedShort = toUnsigned(value)
        // 0x1p16f is 2^16 as float
        return if (unsignedShort == 0xffff) 1f else (unsignedShort / 65536.0f)
    }

    /**
     * Convert signed 16-bit fixed-point to a float between -1 and 1
     *
     * @param value encoded value
     * @return Float value between -1 and 1
     */
    fun i16FixedPointToFloat(value: Short): Float {
        // 0x1p15f is 2^15 as float
        return if (value.toInt() == 0x7fff) 1f else (value / 32768.0f)
    }
}