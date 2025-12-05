package com.genymobile.scrcpy.device

import android.graphics.Rect
import java.util.Objects
import kotlin.math.max

class Size(val width: Int, val height: Int) {
    val max: Int
        get() = max(width.toDouble(), height.toDouble()).toInt()

    fun rotate(): Size {
        return Size(height, width)
    }

    fun limit(maxSize: Int): Size {
        assert(maxSize >= 0) { "Max size may not be negative" }
        assert(maxSize % 8 == 0) { "Max size must be a multiple of 8" }

        if (maxSize == 0) {
            // No limit
            return this
        }

        val portrait = height > width
        val major = if (portrait) height else width
        if (major <= maxSize) {
            return this
        }

        val minor = if (portrait) width else height

        val newMajor = maxSize
        val newMinor = maxSize * minor / major

        val w = if (portrait) newMinor else newMajor
        val h = if (portrait) newMajor else newMinor
        return Size(w, h)
    }

    /**
     * Round both dimensions of this size to be a multiple of 8 (as required by many encoders).
     *
     * @return The current size rounded.
     */
    fun round8(): Size {
        if (isMultipleOf8) {
            // Already a multiple of 8
            return this
        }

        val portrait = height > width
        var major = if (portrait) height else width
        var minor = if (portrait) width else height

        major = major and 7.inv() // round down to not exceed the initial size
        minor = (minor + 4) and 7.inv() // round to the nearest to minimize aspect ratio distortion
        if (minor > major) {
            minor = major
        }

        val w = if (portrait) minor else major
        val h = if (portrait) major else minor
        return Size(w, h)
    }

    val isMultipleOf8: Boolean
        get() = (width and 7) == 0 && (height and 7) == 0

    fun toRect(): Rect {
        return Rect(0, 0, width, height)
    }

    override fun equals(o: Any?): Boolean {
        if (this === o) {
            return true
        }
        if (o == null || javaClass != o.javaClass) {
            return false
        }
        val size = o as Size
        return width == size.width && height == size.height
    }

    override fun hashCode(): Int {
        return Objects.hash(width, height)
    }

    override fun toString(): String {
        return width.toString() + "x" + height
    }
}