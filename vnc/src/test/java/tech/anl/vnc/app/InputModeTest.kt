package tech.anl.vnc.app

import org.junit.Assert.assertEquals
import org.junit.Test

class InputModeTest {
    @Test
    fun fromPref() {
        assertEquals(InputMode.DIRECT_HOLD_PAN, InputMode.fromPref("Direct, Hold Pan"))
        assertEquals(InputMode.DIRECT_SWIPE_PAN, InputMode.fromPref("Direct, Swipe Pan"))
        assertEquals(InputMode.TOUCHPAD, InputMode.fromPref("Touchpad"))
        assertEquals(InputMode.SINGLE_HANDED, InputMode.fromPref("Single Handed"))
        assertEquals(InputMode.DIRECT_HOLD_PAN, InputMode.fromPref(null))
        assertEquals(InputMode.DIRECT_HOLD_PAN, InputMode.fromPref("bogus"))
        for (m in InputMode.values()) assertEquals(m, InputMode.fromPref(m.prefValue))
    }
}
