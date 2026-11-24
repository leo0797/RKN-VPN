package app.rkn.vpn

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en")
class ProfileNameFormatTest {

    @Test
    fun englishDefaultProfileNameUsesIndex() {
        assertEquals(
            "Profile 2",
            RuntimeEnvironment.getApplication().getString(R.string.profile_default_name, 2)
        )
    }

    @Test
    @Config(qualifiers = "ru")
    fun russianDefaultProfileNameUsesIndex() {
        assertEquals(
            "Профиль 2",
            RuntimeEnvironment.getApplication().getString(R.string.profile_default_name, 2)
        )
    }
}
