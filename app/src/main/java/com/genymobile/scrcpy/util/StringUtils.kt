package com.genymobile.scrcpy.util

object StringUtils {
    fun getUtf8TruncationIndex(utf8: ByteArray, maxLength: Int): Int {
        var len = utf8.size
        if (len <= maxLength) {
            return len
        }
        len = maxLength
        // see UTF-8 encoding <https://en.wikipedia.org/wiki/UTF-8#Description>
        while ((utf8[len].toInt() and 0x80) != 0 && (utf8[len].toInt() and 0xc0) != 0xc0) {
            // the next byte is not the start of a new UTF-8 codepoint
            // so if we would cut there, the character would be truncated
            len--
        }
        return len
    }
}