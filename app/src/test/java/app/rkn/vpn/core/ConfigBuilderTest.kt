package app.rkn.vpn.core

import app.rkn.vpn.data.ProxyNode
import app.rkn.vpn.data.RoutingRule
import app.rkn.vpn.data.RuleTarget
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConfigBuilderTest {

    private fun params(node: ProxyNode) = ConfigBuilder.Params(
        mixedPort = 7890,
        apiPort = 9090,
        socksUser = "rkn",
        socksPassword = "secret",
        apiSecret = "api-secret",
        node = node,
        rules = listOf(
            RoutingRule("1", "DOMAIN-SUFFIX", "example.com", RuleTarget.DIRECT),
            RoutingRule("2", "IP-CIDR", "10.0.0.0/8", RuleTarget.DIRECT)
        ),
        routingMode = "rules",
        lanDirect = true
    )

    private val wsLink =
        "vless://11111111-2222-3333-4444-555555555555@example.com:443" +
            "?type=ws&security=tls&sni=example.com&path=%2Fws%3Fed%3D2048&host=example.com#WS"

    @Test
    fun configHasNoJsonOnlyEscapes() {
        val node = LinkParser.parse(wsLink)!!
        val config = ConfigBuilder.build(params(node))

        // org.json на Android пишет «\/», а YAML-парсер mihomo такой escape не понимает.
        assertFalse("в конфиге не должно быть \\/ : $config", config.contains("\\/"))
        assertTrue(config.contains("\"/ws?ed=2048\""))
        assertTrue(config.contains("\"mixed-port\": 7890"))
        assertTrue(config.contains("\"MATCH,PROXY\""))
    }

    @Test
    fun dnsIsEnabledWithDohAndProxyServerNameserver() {
        val node = LinkParser.parse(wsLink)!!
        val config = ConfigBuilder.build(params(node))
        val dns = JSONObject(config).getJSONObject("dns")

        assertTrue(dns.getBoolean("enable"))
        // Не fake-ip: диапазон 240.0.0.0/4 уже занят hev mapdns.
        assertTrue(dns.getString("enhanced-mode") == "redir-host")
        assertTrue(dns.getJSONArray("nameserver").getString(0).startsWith("https://"))
        assertTrue(
            dns.getJSONArray("proxy-server-nameserver")
                .getString(0).startsWith("https://")
        )
        assertTrue(dns.getJSONArray("bootstrap").length() >= 2)
        assertFalse(config.contains("\"dns\": {\r\n  \"enable\": false"))
    }

    @Test
    fun invalidRulesAreDropped() {
        val node = LinkParser.parse(wsLink)!!
        val config = ConfigBuilder.build(
            params(node).copy(
                rules = listOf(
                    RoutingRule("ok-domain", "DOMAIN-SUFFIX", "example.com", RuleTarget.DIRECT),
                    RoutingRule("bad-domain", "DOMAIN", "bad domain.com", RuleTarget.DIRECT),
                    RoutingRule("ok-v4", "IP-CIDR", "10.0.0.0/8", RuleTarget.DIRECT),
                    RoutingRule("bad-v4-no-prefix", "IP-CIDR", "10.0.0.0", RuleTarget.DIRECT),
                    RoutingRule("bad-v4-prefix", "IP-CIDR", "10.0.0.0/99", RuleTarget.DIRECT),
                    RoutingRule("ok-v6", "IP-CIDR6", "fc00::/7", RuleTarget.DIRECT),
                    RoutingRule("bad-v6", "IP-CIDR6", "fc00::/129", RuleTarget.DIRECT)
                )
            )
        )

        assertTrue(config.contains("DOMAIN-SUFFIX,example.com,DIRECT"))
        assertTrue(config.contains("IP-CIDR,10.0.0.0/8,DIRECT,no-resolve"))
        assertTrue(config.contains("IP-CIDR6,fc00::/7,DIRECT,no-resolve"))
        assertFalse(config.contains("bad domain.com"))
        assertFalse(config.contains("IP-CIDR,10.0.0.0,DIRECT"))
        assertFalse(config.contains("10.0.0.0/99"))
        assertFalse(config.contains("fc00::/129"))
    }

    @Test
    fun presetRulesAreAddedOnlyInRulesMode() {
        val node = LinkParser.parse(wsLink)!!
        val withPreset = ConfigBuilder.build(
            params(node).copy(presets = setOf("ru_direct"))
        )
        assertTrue(withPreset.contains("DOMAIN-SUFFIX,yandex.ru,DIRECT"))

        val withoutPreset = ConfigBuilder.build(params(node).copy(presets = emptySet()))
        assertFalse(withoutPreset.contains("DOMAIN-SUFFIX,yandex.ru,DIRECT"))

        val global = ConfigBuilder.build(
            params(node).copy(presets = setOf("ru_direct"), routingMode = "global")
        )
        assertFalse(global.contains("DOMAIN-SUFFIX,yandex.ru,DIRECT"))
    }

    private fun vmessLink(json: String): String =
        "vmess://" + Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))

    /**
     * Кладёт конфиги реальных узлов в build/mihomo-test, чтобы их можно было
     * прогнать через настоящий mihomo (`mihomo -t -f <file>`).
     */
    @Test
    fun writeConfigsForMihomoCheck() {
        val vmessJson = "{\"v\":\"2\",\"ps\":\"VM\",\"add\":\"example.com\",\"port\":\"443\"," +
            "\"id\":\"11111111-2222-3333-4444-555555555555\",\"aid\":\"0\",\"scy\":\"auto\"," +
            "\"net\":\"httpupgrade\",\"type\":\"none\",\"host\":\"example.com\",\"path\":\"/up\"," +
            "\"tls\":\"tls\",\"sni\":\"example.com\"}"

        val links = listOf(
            "hysteria2",
            "hysteria2://ac9bc33d-db20-4d77-a13b-25cbb5592e14@fin.leo797.pro:443" +
                "/?sni=fin.leo797.pro#Finland%20HY2",
            "vless-reality-tcp",
            "vless://ac9bc33d-db20-4d77-a13b-25cbb5592e14@fin.leo797.pro:443?encryption=none&" +
                "flow=xtls-rprx-vision&type=tcp&security=reality&sni=fin.leo797.pro&fp=firefox&" +
                "pbk=zXTQT-b1AXBwOKu9yWMHmeHO_lSZ6lVG5O-NkIy-WF8&sid=0d6037ea074c9f2e#Finland",
            "vless-xhttp",
            "vless://11111111-2222-3333-4444-555555555555@example.com:443?type=xhttp" +
                "&security=reality&sni=example.com&fp=chrome" +
                "&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&sid=ab" +
                "&path=%2Fsplit&mode=auto&extra=%7B%22noGRPCHeader%22%3Atrue%7D#XHTTP",
            "vmess-httpupgrade",
            vmessLink(vmessJson),
            "trojan-ws",
            "trojan://secret@example.com:443?sni=example.com&type=ws&path=%2Fws#T",
            "ss-aes",
            "ss://aes-128-gcm:secret@example.com:8388#S",
            "tuic",
            "tuic://11111111-2222-3333-4444-555555555555:secret@example.com:443" +
                "?sni=example.com&udp_relay_mode=quic&congestion_control=cubic#TUIC",
            "anytls",
            "anytls://secret@example.com:443?sni=example.com#A",
            "wireguard",
            "wireguard://AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA@example.com:51820" +
                "?publickey=BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB&ip=10.0.0.2#WG"
        )

        val outDir = File("build/mihomo-test").apply { mkdirs() }
        links.chunked(2).forEach { (name, link) ->
            val node = LinkParser.parse(link) ?: error("не разобралась ссылка $name")
            File(outDir, "$name.json").writeText(ConfigBuilder.build(params(node)), Charsets.UTF_8)
        }

        outDir.listFiles()?.forEach { file ->
            val text = file.readText()
            assertFalse("${file.name}: не должно быть \\/", text.contains("\\/"))
        }
    }

    @Test
    fun dnsAutoHasDohRuPolicyAndSystemFallback() {
        val node = LinkParser.parse(wsLink)!!
        val config = ConfigBuilder.build(params(node).copy(dnsMode = ConfigBuilder.DNS_AUTO))
        val dns = JSONObject(config).getJSONObject("dns")

        val nameservers = dns.getJSONArray("nameserver")
        assertEquals(2, nameservers.length())
        assertEquals("https://cloudflare-dns.com/dns-query", nameservers.getString(0))
        assertEquals("https://dns.google/dns-query", nameservers.getString(1))

        val policy = dns.getJSONObject("nameserver-policy")
        assertEquals("77.88.8.8", policy.getJSONArray("+.ru").getString(0))
        assertEquals("77.88.8.8", policy.getJSONArray("+.su").getString(0))
        assertEquals("77.88.8.8", policy.getJSONArray("+.xn--p1ai").getString(0))

        val fallback = dns.getJSONArray("fallback")
        assertEquals(1, fallback.length())
        assertEquals("system", fallback.getString(0))
    }

    @Test
    fun dnsCloudflareModeUsesCloudflareNameserver() {
        val node = LinkParser.parse(wsLink)!!
        val config = ConfigBuilder.build(params(node).copy(dnsMode = ConfigBuilder.DNS_CLOUDFLARE))
        val dns = JSONObject(config).getJSONObject("dns")
        val nameservers = dns.getJSONArray("nameserver")
        assertEquals(1, nameservers.length())
        assertEquals("https://cloudflare-dns.com/dns-query", nameservers.getString(0))
        assertEquals("system", dns.getJSONArray("fallback").getString(0))
        assertTrue(dns.has("nameserver-policy"))
    }

    @Test
    fun dnsGoogleModeUsesGoogleNameserver() {
        val node = LinkParser.parse(wsLink)!!
        val config = ConfigBuilder.build(params(node).copy(dnsMode = ConfigBuilder.DNS_GOOGLE))
        val dns = JSONObject(config).getJSONObject("dns")
        val nameservers = dns.getJSONArray("nameserver")
        assertEquals(1, nameservers.length())
        assertEquals("https://dns.google/dns-query", nameservers.getString(0))
    }

    @Test
    fun dnsCustomValidUsesCustomAndKeepsPolicyFallback() {
        val node = LinkParser.parse(wsLink)!!
        val custom = "https://dns.example/dns-query"
        val config = ConfigBuilder.build(
            params(node).copy(dnsMode = ConfigBuilder.DNS_CUSTOM, dnsCustom = custom)
        )
        val dns = JSONObject(config).getJSONObject("dns")
        val nameservers = dns.getJSONArray("nameserver")
        assertEquals(1, nameservers.length())
        assertEquals(custom, nameservers.getString(0))
        assertEquals("77.88.8.8", dns.getJSONObject("nameserver-policy").getJSONArray("+.ru").getString(0))
        assertEquals("system", dns.getJSONArray("fallback").getString(0))
    }

    @Test
    fun dnsCustomInvalidFallsBackToSafeNameservers() {
        val node = LinkParser.parse(wsLink)!!
        val config = ConfigBuilder.build(
            params(node).copy(dnsMode = ConfigBuilder.DNS_CUSTOM, dnsCustom = "not a dns;drop")
        )
        val dns = JSONObject(config).getJSONObject("dns")
        val nameservers = dns.getJSONArray("nameserver")
        assertEquals(2, nameservers.length())
        assertEquals("https://cloudflare-dns.com/dns-query", nameservers.getString(0))
        assertEquals("https://dns.google/dns-query", nameservers.getString(1))
    }

    @Test
    fun isValidCustomDnsAcceptsSafeValuesAndRejectsInvalid() {
        assertTrue(ConfigBuilder.isValidCustomDns("1.1.1.1"))
        assertTrue(ConfigBuilder.isValidCustomDns("8.8.8.8"))
        assertTrue(ConfigBuilder.isValidCustomDns("https://dns.google/dns-query"))
        assertTrue(ConfigBuilder.isValidCustomDns("tls://1.1.1.1"))
        assertTrue(ConfigBuilder.isValidCustomDns("quic://dns.adguard.com"))
        assertTrue(ConfigBuilder.isValidCustomDns("system"))

        assertFalse(ConfigBuilder.isValidCustomDns(""))
        assertFalse(ConfigBuilder.isValidCustomDns("example.com"))
        assertFalse(ConfigBuilder.isValidCustomDns("http://1.1.1.1"))
        assertFalse(ConfigBuilder.isValidCustomDns("https://"))
        assertFalse(ConfigBuilder.isValidCustomDns("1.1.1.1,8.8.8.8"))
        assertFalse(ConfigBuilder.isValidCustomDns("256.0.0.1"))
    }
}