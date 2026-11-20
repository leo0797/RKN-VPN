package app.rkn.vpn.data

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PrefsProfilesTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("rkn_vpn_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun migratesLegacySingleSubscriptionToFirstProfile() {
        val sp = context.getSharedPreferences("rkn_vpn_prefs", Context.MODE_PRIVATE)
        val nodes = listOf(
            ProxyNode(
                id = "n1",
                name = "Node 1",
                protocol = "vless",
                server = "example.com",
                port = 443,
                raw = """{"type":"vless","name":"Node 1","server":"example.com","server_port":443}"""
            )
        )
        val sub = SubscriptionInfo(title = "My Sub", updatedAt = 123L)
        sp.edit()
            .putString("sub_url", "https://sub.example.com/token")
            .putString("sub_info", subscriptionToJson(sub))
            .putString("nodes", nodesToJson(nodes))
            .putString("selected", "n1")
            .commit()

        val prefs = Prefs(context)

        assertEquals(1, prefs.profiles.size)
        val profile = prefs.profiles.first()
        assertEquals(Prefs.DEFAULT_PROFILE_ID, profile.id)
        assertEquals("https://sub.example.com/token", prefs.profileUrl(profile.id))
        assertEquals("My Sub", prefs.subscription.title)
        assertEquals(1, prefs.nodes.size)
        assertEquals("n1", prefs.selectedId)
        assertEquals(profile.id, prefs.activeProfileId)
        // legacy keys removed
        assertEquals(null, sp.getString("sub_info", null))
        assertEquals(null, sp.getString("nodes", null))
        assertEquals(null, sp.getString("selected", null))
    }

    @Test
    fun switchesActiveProfileAndExposesItsData() {
        val prefs = Prefs(context)
        // after migration there is a default profile
        val p1 = prefs.activeProfile()
        assertNotNull(p1)

        val p2 = prefs.createProfile(name = "Second", url = "https://second.example/sub")
        prefs.updateProfile(
            p2.copy(
                subscription = SubscriptionInfo(title = "Second Sub"),
                nodes = listOf(
                    ProxyNode("n2", "N2", "vmess", "s2.example", 443, """{"type":"vmess","name":"N2"}""")
                ),
                selectedId = "n2"
            )
        )

        // still active is p1 by default
        prefs.switchToProfileForTest(p1!!.id)
        assertEquals(p1.id, prefs.activeProfileId)

        prefs.switchToProfileForTest(p2.id)
        assertEquals(p2.id, prefs.activeProfileId)
        assertEquals("https://second.example/sub", prefs.subscriptionUrl)
        assertEquals("Second Sub", prefs.subscription.title)
        assertEquals("n2", prefs.selectedId)
        assertEquals(1, prefs.nodes.size)

        prefs.switchToProfileForTest(p1.id)
        assertEquals(p1.id, prefs.activeProfileId)
        assertEquals("", prefs.subscriptionUrl)
    }

    private fun Prefs.switchToProfileForTest(id: String) {
        this.activeProfileId = id
    }

    @Test
    fun storesConnectOnLaunchSetting() {
        val prefs = Prefs(context)
        assertEquals(false, prefs.connectOnLaunch)
        prefs.connectOnLaunch = true
        assertEquals(true, prefs.connectOnLaunch)
        val prefs2 = Prefs(context)
        assertEquals(true, prefs2.connectOnLaunch)
    }
}