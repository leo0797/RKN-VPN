package app.rkn.vpn.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SystemKillSwitchTest {

    private fun reader(app: String?, lockdown: String?) = SystemKillSwitch.SettingReader { key ->
        when (key) {
            "always_on_vpn_app" -> app
            "always_on_vpn_lockdown" -> lockdown
            else -> null
        }
    }

    @Test
    fun onWhenOurAppIsAlwaysOnAndLockdownEnabled() {
        assertTrue(SystemKillSwitch.isOn(reader("app.rkn.vpn", "1"), "app.rkn.vpn"))
    }

    @Test
    fun offWhenAlwaysOnBelongsToAnotherApp() {
        assertFalse(SystemKillSwitch.isOn(reader("com.other.vpn", "1"), "app.rkn.vpn"))
    }

    @Test
    fun offWhenLockdownDisabledOrMissing() {
        assertFalse(SystemKillSwitch.isOn(reader("app.rkn.vpn", "0"), "app.rkn.vpn"))
        assertFalse(SystemKillSwitch.isOn(reader("app.rkn.vpn", null), "app.rkn.vpn"))
    }

    /**
     * Регрессия вылета после онбординга на Android 12+: скрытый ключ
     * always_on_vpn_app недоступен и чтение бросает SecurityException.
     */
    @Test
    fun restrictedSettingsDoNotCrash() {
        val restricted = SystemKillSwitch.SettingReader { _ ->
            throw SecurityException("Settings key: <always_on_vpn_app> is not readable")
        }
        assertFalse(SystemKillSwitch.isOn(restricted, "app.rkn.vpn"))
    }
}
