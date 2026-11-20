package app.rkn.vpn.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServersScreenUtilsTest {

    @Test
    fun extractsLeadingFlagEmoji() {
        assertEquals("🇫🇮", leadingFlagEmoji("🇫🇮 Finland HY2"))
        assertEquals("🇺🇸", leadingFlagEmoji("🇺🇸 USA"))
        assertEquals("🇷🇺", leadingFlagEmoji("  🇷🇺 Russia"))
    }

    @Test
    fun returnsNullWhenNoLeadingFlag() {
        assertNull(leadingFlagEmoji("Finland 🇫🇮"))
        assertNull(leadingFlagEmoji("No flag"))
        assertNull(leadingFlagEmoji(""))
        assertNull(leadingFlagEmoji("F"))
    }
}
