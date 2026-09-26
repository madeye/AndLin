package tech.anl.vnc.app

/** Touch input modes. [prefValue] is the value stored by the settings ListPreference. */
enum class InputMode(val prefValue: String) {
    DIRECT_HOLD_PAN("Direct, Hold Pan"),
    DIRECT_SWIPE_PAN("Direct, Swipe Pan"),
    TOUCHPAD("Touchpad"),
    SINGLE_HANDED("Single Handed");

    /** True if the pointer follows the finger; false for relative (touchpad-like) modes. */
    val isDirect: Boolean get() = this == DIRECT_HOLD_PAN || this == DIRECT_SWIPE_PAN

    companion object {
        fun fromPref(v: String?): InputMode =
            values().firstOrNull { it.prefValue.equals(v?.trim(), ignoreCase = true) || it.name == v }
                ?: DIRECT_HOLD_PAN
    }
}
