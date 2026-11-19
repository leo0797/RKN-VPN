package app.rkn.vpn.core

import app.rkn.vpn.data.ProxyNode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConfigImporterTest {

    private val clashYaml = """
        # Конфиг Clash/Mihomo
        mixed-port: 7890
        mode: rule
        proxies:
          # VLESS + Reality
          - name: "VLESS Reality"
            type: vless
            server: fin.example.com
            port: 443
            uuid: 11111111-2222-3333-4444-555555555555
            tls: true
            servername: fin.example.com
            skip-cert-verify: false
            client-fingerprint: firefox
            alpn: [h2, http/1.1]
            udp: true
            flow: xtls-rprx-vision
            network: tcp
            reality-opts:
              public-key: zXTQT-b1AXBwOKu9yWMHmeHO_lSZ6lVG5O-NkIy-WF8
              short-id: 0d6037ea074c9f2e
          - name: SS-node
            type: ss
            server: ss.example.com
            port: 8388
            cipher: aes-256-gcm
            password: "p@ss:word" # пароль с комментарием
            udp: true
          - name: HY2
            type: hysteria2
            server: hy2.example.com
            port: 8443
            password: secret
            obfs: salamander
            obfs-password: obfs-secret
            up: "50 Mbps"
            down: "200 Mbps"
          - name: Unsupported
            type: snell
            server: bad.example.com
            port: 443
          - name: BadPort
            type: vless
            server: bad.example.com
            port: 70000
        proxy-groups:
          - name: SELECT
            type: select
            proxies: [VLESS Reality, SS-node]
        rules:
          - MATCH,DIRECT
    """.trimIndent()

    private val sip008 = """
        {
          "version": 1,
          "servers": [
            {
              "server": "sip1.example.com",
              "server_port": 8388,
              "password": "pw",
              "method": "aes-256-gcm",
              "remarks": "SIP One",
              "plugin": "obfs-local",
              "plugin_opts": "obfs=http;host=example.com"
            },
            {
              "server": "sip2.example.com",
              "server_port": 443,
              "password": "pw2",
              "method": "chacha20-ietf-poly1305",
              "title": "SIP Two",
              "plugin": "v2ray-plugin",
              "plugin_opts": "mode=websocket;host=cdn.example.com;path=/ws;tls=true"
            },
            {
              "server": "",
              "server_port": 8388,
              "password": "x",
              "method": "none",
              "remarks": "bad"
            }
          ]
        }
    """.trimIndent()

    private val vlessLink =
        "vless://11111111-2222-3333-4444-555555555555@example.com:443" +
            "?encryption=none&flow=xtls-rprx-vision&type=tcp&security=reality" +
            "&sni=example.com&fp=firefox" +
            "&pbk=zXTQT-b1AXBwOKu9yWMHmeHO_lSZ6lVG5O-NkIy-WF8&sid=0d6037ea074c9f2e#Link"

    private fun byName(nodes: List<ProxyNode>, name: String): ProxyNode =
        nodes.first { it.name == name }

    @Test
    fun detectsFormats() {
        assertTrue(ConfigImporter.looksLikeClashYaml("\uFEFF\n  \n" + clashYaml))
        assertFalse(ConfigImporter.looksLikeClashYaml(sip008))
        assertFalse(ConfigImporter.looksLikeClashYaml(vlessLink))
        assertTrue(ConfigImporter.looksLikeSip008(sip008))
        assertFalse(ConfigImporter.looksLikeSip008(clashYaml))
        assertFalse(ConfigImporter.looksLikeSip008("{\"servers\": []}"))
    }

    @Test
    fun parsesClashProxies() {
        val nodes = ConfigImporter.parseClashYaml(clashYaml)
        assertEquals(
            "Должны разобраться три валидных узла",
            setOf("VLESS Reality", "SS-node", "HY2"),
            nodes.map { it.name }.toSet()
        )
        assertEquals(3, nodes.size)
        assertEquals(setOf("vless", "ss", "hysteria2"), nodes.map { it.protocol }.toSet())
        assertTrue(nodes.all { it.id.startsWith("clash-") })
        assertEquals(443, byName(nodes, "VLESS Reality").port)
        assertEquals(8388, byName(nodes, "SS-node").port)
        assertEquals(8443, byName(nodes, "HY2").port)
    }

    @Test
    fun clashRawIsMihomoOutbound() {
        val nodes = ConfigImporter.parseClashYaml(clashYaml)
        val vless = JSONObject(byName(nodes, "VLESS Reality").raw)
        assertEquals("vless", vless.getString("type"))
        assertEquals("fin.example.com", vless.getString("server"))
        assertEquals(443, vless.getInt("port"))
        assertEquals("11111111-2222-3333-4444-555555555555", vless.getString("uuid"))
        assertTrue(vless.getBoolean("tls"))
        assertFalse(vless.getBoolean("skip-cert-verify"))
        assertEquals("firefox", vless.getString("client-fingerprint"))
        assertEquals("h2", vless.getJSONArray("alpn").getString(0))
        assertEquals(
            "zXTQT-b1AXBwOKu9yWMHmeHO_lSZ6lVG5O-NkIy-WF8",
            vless.getJSONObject("reality-opts").getString("public-key")
        )
        assertEquals("0d6037ea074c9f2e", vless.getJSONObject("reality-opts").getString("short-id"))

        val ss = JSONObject(byName(nodes, "SS-node").raw)
        assertEquals("ss", ss.getString("type"))
        assertEquals("aes-256-gcm", ss.getString("cipher"))
        assertEquals("p@ss:word", ss.getString("password"))
        assertTrue(ss.getBoolean("udp"))

        val hy2 = JSONObject(byName(nodes, "HY2").raw)
        assertEquals("salamander", hy2.getString("obfs"))
        assertEquals("obfs-secret", hy2.getString("obfs-password"))
        assertEquals("50 Mbps", hy2.getString("up"))
    }

    @Test
    fun parsesSip008WithPlugins() {
        val nodes = ConfigImporter.parseSip008(sip008)
        assertEquals(2, nodes.size)
        assertEquals(setOf("SIP One", "SIP Two"), nodes.map { it.name }.toSet())
        assertTrue(nodes.all { it.protocol == "ss" && it.id.startsWith("sip-") })

        val obfs = JSONObject(byName(nodes, "SIP One").raw)
        assertEquals("ss", obfs.getString("type"))
        assertEquals("aes-256-gcm", obfs.getString("cipher"))
        assertEquals(8388, obfs.getInt("port"))
        assertEquals("obfs", obfs.getString("plugin"))
        assertEquals("http", obfs.getJSONObject("plugin-opts").getString("mode"))
        assertEquals("example.com", obfs.getJSONObject("plugin-opts").getString("host"))

        val v2ray = JSONObject(byName(nodes, "SIP Two").raw)
        assertEquals("v2ray-plugin", v2ray.getString("plugin"))
        val opts = v2ray.getJSONObject("plugin-opts")
        assertEquals("websocket", opts.getString("mode"))
        assertEquals("cdn.example.com", opts.getString("host"))
        assertEquals("/ws", opts.getString("path"))
        assertTrue(opts.getBoolean("tls"))
    }

    @Test
    fun importDetectsAllThreeFormats() {
        assertEquals(3, ConfigImporter.`import`(clashYaml).size)
        assertEquals(2, ConfigImporter.`import`(sip008).size)

        val links = ConfigImporter.`import`(vlessLink)
        assertEquals(1, links.size)
        assertEquals("vless", links.first().protocol)

        val encoded = Base64.getEncoder().encodeToString(vlessLink.toByteArray(Charsets.UTF_8))
        val fromBase64 = ConfigImporter.`import`(encoded)
        assertEquals(1, fromBase64.size)
        assertEquals(links.first().id, fromBase64.first().id)
    }

    @Test
    fun malformedInputDoesNotThrow() {
        assertTrue(ConfigImporter.parseClashYaml("not a yaml").isEmpty())
        assertTrue(ConfigImporter.parseSip008("not a json").isEmpty())
        assertTrue(ConfigImporter.`import`("").isEmpty())
        assertTrue(ConfigImporter.`import`("not-a-link").isEmpty())
        assertEquals(
            3,
            ConfigImporter.parseClashYaml(
                clashYaml.replace("password: \"p@ss:word\"", "reality-opts: [unclosed")
            ).size
        )
    }
}
