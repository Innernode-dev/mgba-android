package com.retro.mgba.ui.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinkAddressParserTest {

    @Test
    fun parseHostAndPort_splitsEmbeddedPort() {
        assertEquals("192.168.1.42" to 55777, parseHostAndPort("192.168.1.42:55777", "12345"))
        assertEquals("100.64.0.12" to 60000, parseHostAndPort("  100.64.0.12:60000  ", ""))
    }

    @Test
    fun parseHostAndPort_usesSeparatePortField() {
        assertEquals("192.168.0.10" to 55777, parseHostAndPort("192.168.0.10", "55777"))
    }

    @Test
    fun parseHostAndPort_rejectsInvalidInputs() {
        assertNull(parseHostAndPort("", "55777"))
        assertNull(parseHostAndPort("   ", "55777"))
        assertNull(parseHostAndPort("192.168.1.1", "80"))
        assertNull(parseHostAndPort("192.168.1.1", "70000"))
        assertNull(parseHostAndPort("192.168.1.1:0", "55777"))
        assertNull(parseHostAndPort("192.168.1.1", "abc"))
    }
}
