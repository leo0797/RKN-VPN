package app.rkn.vpn.core

import android.content.Context
import android.net.Uri
import app.rkn.vpn.data.Prefs
import app.rkn.vpn.data.ProxyNode
import app.rkn.vpn.data.SubscriptionProfile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeepLinkCoordinatorTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private val vlessLink =
        "vless://11111111-2222-3333-4444-555555555555@fin.example.com:443" +
            "?encryption=none&type=tcp#Finland"

    private lateinit var prefs: Prefs
    private lateinit var originalProfiles: List<SubscriptionProfile>
    private lateinit var originalNodes: List<ProxyNode>

    @Before
    fun setUp() {
        context.getSharedPreferences("rkn_vpn_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        prefs = Prefs(context)
        val node = ProxyNode(
            id = "existing",
            name = "Existing",
            protocol = "vless",
            server = "old.example.com",
            port = 443,
            raw = """{"type":"vless","name":"Existing"}"""
        )
        val profile = prefs.activeProfile() ?: error("default profile is missing")
        prefs.updateProfile(
            profile.copy(name = "Main", nodes = listOf(node), selectedId = node.id)
        )
        originalProfiles = prefs.profiles
        originalNodes = prefs.nodes
    }

    private fun coordinator(
        onConfirmationRequired: (DeepLinkTarget) -> Unit = {},
        profileName: (String) -> String = { it },
        addNodes: (String, String) -> Boolean = { _, _ -> true },
        addSubscription: suspend (String, String) -> Boolean = { _, _ -> true }
    ) = DeepLinkCoordinator(
        onConfirmationRequired = onConfirmationRequired,
        profileName = profileName,
        addNodes = addNodes,
        addSubscription = addSubscription
    )

    @Test
    fun handleDeepLinkWithoutConfirmationDoesNotApplyAnything() {
        var confirmations = 0
        var nodeImports = 0
        var subscriptionImports = 0
        val links = coordinator(
            onConfirmationRequired = { confirmations++ },
            addNodes = { _, _ -> nodeImports++; true },
            addSubscription = { _, _ -> subscriptionImports++; true }
        )

        assertTrue(links.handleDeepLink(Uri.parse(vlessLink)))
        assertTrue(
            links.handleDeepLink(
                Uri.parse("clash://install-config?url=http%3A%2F%2Fsub.example.com%2Ftoken")
            )
        )

        assertEquals(2, confirmations)
        assertEquals(0, nodeImports)
        assertEquals(0, subscriptionImports)
        // Ни профили, ни узлы не должны меняться до нажатия «Добавить».
        assertEquals(originalProfiles, prefs.profiles)
        assertEquals(originalNodes, prefs.nodes)
    }

    @Test
    fun cancelDiscardsPendingLink() = runBlocking {
        var imports = 0
        val links = coordinator(addNodes = { _, _ -> imports++; true })

        assertTrue(links.handleDeepLink(Uri.parse(vlessLink)))
        links.cancel()

        assertFalse(links.confirm())
        assertEquals(0, imports)
        assertEquals(originalProfiles, prefs.profiles)
    }

    @Test
    fun confirmAppliesOnlyPendingLink() = runBlocking {
        var applied: Pair<String, String>? = null
        val links = coordinator(
            profileName = { "Import from $it" },
            addNodes = { value, name -> applied = value to name; true }
        )

        assertTrue(links.handleDeepLink(Uri.parse(vlessLink)))
        assertTrue(links.confirm())

        assertEquals(vlessLink, applied?.first)
        assertEquals("Import from VLESS", applied?.second)
    }

    @Test
    fun parsesClashUrlAndWarnsAboutHttp() {
        val target = DeepLinkParser.parse(
            Uri.parse("clash://install-config?url=http%3A%2F%2Fsub.example.com%2Ftoken")
        )

        assertNotNull(target)
        target!!
        assertEquals("CLASH", target.source)
        assertTrue(target.isSubscriptionUrl)
        assertTrue(target.insecureHttp)
        assertNull(target.nodeCount)
    }

    @Test
    fun parsesEncodedShareLinkFromRknvpnPath() {
        val target = DeepLinkParser.parse(
            Uri.parse("rknvpn://add/${Uri.encode(vlessLink)}")
        )

        assertNotNull(target)
        target!!
        assertEquals("RKN VPN", target.source)
        assertFalse(target.isSubscriptionUrl)
        assertFalse(target.insecureHttp)
        assertEquals(1, target.nodeCount)
    }
}
