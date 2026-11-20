package app.rkn.vpn.util

import org.junit.Assert.assertEquals
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
        assertEquals(
            SystemKillSwitch.Status.ON,
            SystemKillSwitch.status(reader("app.rkn.vpn", "1"), "app.rkn.vpn")
        )
    }

    @Test
    fun offWhenAlwaysOnBelongsToAnotherApp() {
        assertEquals(
            SystemKillSwitch.Status.OFF,
            SystemKillSwitch.status(reader("com.other.vpn", "1"), "app.rkn.vpn")
        )
    }

    @Test
    fun offWhenLockdownDisabledOrMissing() {
        assertEquals(
            SystemKillSwitch.Status.OFF,
            SystemKillSwitch.status(reader("app.rkn.vpn", "0"), "app.rkn.vpn")
        )
        assertEquals(
            SystemKillSwitch.Status.OFF,
            SystemKillSwitch.status(reader("app.rkn.vpn", null), "app.rkn.vpn")
        )
    }

    @Test
    fun unknownWhenSettingsUnavailable() {
        assertEquals(
            SystemKillSwitch.Status.UNKNOWN,
            SystemKillSwitch.status(reader(null, null), "app.rkn.vpn")
        )
    }

    /**
     * Регрессия вылета после онбординга на Android 12+: скрытый ключ
     * always_on_vpn_app недоступен и чтение бросает SecurityException.
     */
    @Test
    fun restrictedSettingsReturnUnknown() {
        val restricted = SystemKillSwitch.SettingReader { _ ->
            throw SecurityException("Settings key: <always_on_vpn_app> is not readable")
        }
        assertEquals(
            SystemKillSwitch.Status.UNKNOWN,
            SystemKillSwitch.status(restricted, "app.rkn.vpn")
        )
    }

    @Test
    fun serviceStatusReflectsAlwaysOnAndLockdown() {
        assertEquals(SystemKillSwitch.Status.ON, SystemKillSwitch.serviceStatus(true, true))
        assertEquals(SystemKillSwitch.Status.OFF, SystemKillSwitch.serviceStatus(true, false))
        assertEquals(SystemKillSwitch.Status.OFF, SystemKillSwitch.serviceStatus(false, true))
        assertEquals(SystemKillSwitch.Status.OFF, SystemKillSwitch.serviceStatus(false, false))
    }
}
