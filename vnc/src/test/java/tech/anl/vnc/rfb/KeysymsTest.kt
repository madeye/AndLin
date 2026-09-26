package tech.anl.vnc.rfb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeysymsTest {
    @Test
    fun mapsCharacters() {
        assertEquals(0x61, Keysyms.forCodePoint('a'.code))
        assertEquals(0x41, Keysyms.forCodePoint('A'.code))
        assertEquals(0x20, Keysyms.forCodePoint(' '.code))
        assertEquals(0xe9, Keysyms.forCodePoint('é'.code))
        assertEquals(Keysyms.RETURN, Keysyms.forCodePoint('\n'.code))
        assertEquals(Keysyms.TAB, Keysyms.forCodePoint('\t'.code))
        assertEquals(0x010020ac, Keysyms.forCodePoint('€'.code))
        assertEquals(0x01004e2d, Keysyms.forCodePoint('中'.code))
        assertEquals(0, Keysyms.forCodePoint(0x01))
    }

    @Test
    fun modifiers() {
        assertTrue(Keysyms.isModifier(Keysyms.CONTROL_L))
        assertTrue(Keysyms.isModifier(Keysyms.SUPER_L))
        assertEquals(0xffbf, Keysyms.function(2))
    }
}
