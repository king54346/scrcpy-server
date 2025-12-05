package com.genymobile.scrcpy.device

enum class Orientation(val value: String) {  // 改用 value 或其他名称
    // @formatter:off
    Orient0("0"),
    Orient90("90"),
    Orient180("180"),
    Orient270("270"),
    Flip0("flip0"),
    Flip90("flip90"),
    Flip180("flip180"),
    Flip270("flip270");

    enum class Lock {
        Unlocked, LockedInitial, LockedValue,
    }

    val isFlipped: Boolean
        get() = (ordinal and 4) != 0

    val rotation: Int
        get() = ordinal and 3

    companion object {
        fun getByName(name: String): Orientation {
            for (orientation in entries) {
                if (orientation.value == name) {  // 这里改用 value
                    return orientation
                }
            }
            throw IllegalArgumentException("Unknown orientation: $name")
        }

        fun fromRotation(ccwRotation: Int): Orientation {
            require(ccwRotation in 0..3) { "Rotation must be between 0 and 3" }
            // Display rotation is expressed counter-clockwise, orientation is expressed clockwise
            val cwRotation = (4 - ccwRotation) % 4
            return entries[cwRotation]
        }
    }
}