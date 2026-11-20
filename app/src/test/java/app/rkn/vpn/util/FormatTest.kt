package app.rkn.vpn.util

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {

    @Test
    fun initialTakesWholeUnicodeCodePoint() {
        assertEquals("\uD83C\uDDEB", Format.initial("🇫🇮 Finland HY2"))
        assertEquals("🙂", Format.initial("🙂 Server"))
    }

    @Test
    fun initialUppercasesRegularLetters() {
        assertEquals("F", Format.initial("finland"))
        assertEquals("М", Format.initial("москва"))
    }

    @Test
    fun initialHandlesEmptyText() {
        assertEquals("", Format.initial(""))
    }
}
