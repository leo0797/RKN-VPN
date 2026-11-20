package app.rkn.vpn

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.rkn.vpn.core.HevTunnel
import app.rkn.vpn.data.SecureStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Минимальный прогон на реальном эмуляторе. Главная цель — старые API 24–28,
 * где AAR hev-socks5-tunnel официально объявляет minSdk 29 и подключается
 * через tools:overrideLibrary. JVM-тесты такую ошибку не поймают.
 */
@RunWith(AndroidJUnit4::class)
class SmokeTest {

    @Test
    fun applicationStarts() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("app.rkn.vpn", context.packageName)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(activity.findViewById<android.view.View>(android.R.id.content) != null)
            }
        }
    }

    @Test
    fun tunnelConfigIsBuilt() {
        val config = HevTunnel.buildConfig(7890, "user", "password")
        assertTrue(config.contains("address: 198.18.0.2"))
        assertTrue(config.contains("port: 7890"))
    }

    /** Регрессия Keystore: IV должен приходить от cipher, а не от вызывающего. */
    @Test
    fun secureStorageRoundTrip() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sp = context.getSharedPreferences("secure_storage_test", Context.MODE_PRIVATE)
        sp.edit().clear().commit()
        val storage = SecureStorage(sp)
        val secret = "https://sub.example.com/token?key=secret"
        storage.put("url", secret)
        assertEquals(secret, storage.get("url"))
        assertTrue(sp.getString("url", null)!!.startsWith("enc:"))
    }
}
