package app.rkn.vpn.core

import android.net.Uri
import app.rkn.vpn.data.ProxyNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LinkParserTest {

    /** Тело реальной тестовой подписки Remnawave (base64 со ссылками). */
    private val subscriptionBody: String by lazy {
        javaClass.getResourceAsStream("/sub-test.b64")!!
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
            .trim()
    }

    private val uuid = "11111111-2222-3333-4444-555555555555"
    private val realityPbk = "zXTQT-b1AXBwOKu9yWMHmeHO_lSZ6lVG5O-NkIy-WF8"

    private val vlessLink =
        "vless://ac9bc33d-db20-4d77-a13b-25cbb5592e14@fin.leo797.pro:443?encryption=none&" +
            "flow=xtls-rprx-vision&type=tcp&security=reality&sni=fin.leo797.pro&fp=firefox&" +
            "pbk=$realityPbk&sid=0d6037ea074c9f2e#Finland"

    private fun vmessLink(json: String): String =
        "vmess://" + Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))

    private val wgPrivate = Base64.getEncoder().encodeToString(ByteArray(32))
    private val wgPublic = Base64.getEncoder().encodeToString(ByteArray(32) { 7 })

    @Test
    fun subscriptionContainsBothNodes() {
        val nodes = LinkParser.parseSubscription(subscriptionBody)
        assertEquals(
            "Ожидались vless и hysteria2, получено ${nodes.map { it.protocol }}",
            setOf("vless", "hysteria2"),
            nodes.map { it.protocol }.toSet()
        )
        assertEquals("В подписке два узла", 2, nodes.size)
    }

    @Test
    fun vlessRealityParses() {
        val node = LinkParser.parse(vlessLink)
        assertNotNull("VLESS Reality должен разбираться", node)
        node!!
        assertEquals("vless", node.protocol)
        assertEquals("fin.leo797.pro", node.server)
        assertEquals(443, node.port)
        assertTrue("в raw должен быть reality-opts: ${node.raw}", node.raw.contains("reality-opts"))
        assertTrue("в raw должен быть flow: ${node.raw}", node.raw.contains("xtls-rprx-vision"))
    }

    @Test
    fun unsupportedLinkReturnsNull() {
        assertNull(LinkParser.parse("ftp://example.com/file"))
    }

    @Test
    fun xhttpVlessParses() {
        val link = "vless://$uuid@example.com:443" +
            "?type=xhttp&security=reality&sni=example.com&fp=chrome" +
            "&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&sid=ab" +
            "&path=%2Fsplit&mode=auto&extra=%7B%22noGRPCHeader%22%3Atrue%7D#XHTTP"
        val node: ProxyNode? = LinkParser.parse(link)
        assertNotNull("VLESS XHTTP должен разбираться", node)
        assertEquals("vless", node!!.protocol)
        assertTrue(node.raw.contains("xhttp-opts"))
        assertTrue(node.raw.contains("reality-opts"))
    }

    // ------------------------------------------------------------ REALITY

    @Test
    fun realityWithBrokenKeysIsRejected() {
        assertNull(
            "pbk не из 32 байт должен отбрасываться",
            LinkParser.parse(vlessLink.replace("pbk=$realityPbk", "pbk=AAAA"))
        )
        assertNull(
            "sid нечётной длины должен отбрасываться",
            LinkParser.parse(vlessLink.replace("sid=0d6037ea074c9f2e", "sid=0d603"))
        )
        assertNull(
            "sid длиннее 16 hex должен отбрасываться",
            LinkParser.parse(vlessLink.replace("sid=0d6037ea074c9f2e", "sid=00112233445566778899"))
        )
    }

    @Test
    fun realityPublicKeyWithPaddingIsAccepted() {
        val node = LinkParser.parse(vlessLink.replace("pbk=$realityPbk", "pbk=$realityPbk%3D"))
        assertNotNull(node)
        assertTrue(node!!.raw.contains(realityPbk))
    }

    // -------------------------------------------------------------- VLESS

    @Test
    fun invalidVlessEncryptionIsDropped() {
        val link = "vless://$uuid@example.com:443?security=tls&sni=example.com" +
            "&encryption=definitely-not-mlkem#E"
        val node = LinkParser.parse(link)
        assertNotNull(node)
        assertFalse("битый encryption не должен попадать в конфиг", node!!.raw.contains("\"encryption\""))
    }

    @Test
    fun xhttpUnknownModeIsDropped() {
        val link = "vless://$uuid@example.com:443?type=xhttp&security=reality&sni=example.com" +
            "&pbk=$realityPbk&sid=ab&mode=bogus#X"
        val node = LinkParser.parse(link)
        assertNotNull(node)
        assertFalse("неизвестный xhttp mode ломает mihomo", node!!.raw.contains("\"mode\""))
    }

    // -------------------------------------------------------------- VMess

    @Test
    fun vmessHttpUpgradeAndCipherAreNormalized() {
        val json = "{\"v\":\"2\",\"ps\":\"VM\",\"add\":\"example.com\",\"port\":\"443\"," +
            "\"id\":\"$uuid\",\"aid\":\"0\",\"scy\":\"bogus\",\"net\":\"httpupgrade\"," +
            "\"type\":\"none\",\"host\":\"example.com\",\"path\":\"/up\"," +
            "\"tls\":\"tls\",\"sni\":\"example.com\"}"
        val node = LinkParser.parse(vmessLink(json))
        assertNotNull(node)
        assertTrue("cipher должен стать auto: ${node!!.raw}", node.raw.contains("\"cipher\":\"auto\""))
        assertTrue("httpupgrade должен стать ws+v2ray-http-upgrade", node.raw.contains("v2ray-http-upgrade"))
    }

    @Test
    fun vmessWithInvalidPortIsRejected() {
        val json = """{"v":"2","ps":"VM","add":"example.com","port":"70000","id":"$uuid"}"""
        assertNull(LinkParser.parse(vmessLink(json)))
    }

    // ------------------------------------------------------------- Trojan

    @Test
    fun trojanWithWsParses() {
        val node = LinkParser.parse(
            "trojan://secret@example.com:443?sni=example.com&type=ws&path=%2Fws&host=example.com#T"
        )
        assertNotNull(node)
        assertEquals("trojan", node!!.protocol)
        assertTrue(node.raw.contains("ws-opts"))
    }

    // -------------------------------------------------------- Shadowsocks

    @Test
    fun shadowsocksCipherIsNormalized() {
        val node = LinkParser.parse("ss://chacha20-poly1305:secret@example.com:8388#SS")
        assertNotNull(node)
        assertTrue(node!!.raw.contains("chacha20-ietf-poly1305"))
        assertNull("неизвестный cipher должен отбрасываться", LinkParser.parse("ss://bogus:secret@example.com:8388"))
    }

    @Test
    fun shadowsocks2022KeyIsValidated() {
        val key16 = Base64.getEncoder().encodeToString(ByteArray(16))
        val ok = LinkParser.parse(
            "ss://2022-blake3-aes-128-gcm:${Uri.encode(key16)}@example.com:8388#SS2022"
        )
        assertNotNull("ключ 16 байт для aes-128-gcm должен приниматься", ok)
        assertTrue(ok!!.raw.contains(key16))

        val key32 = Base64.getEncoder().encodeToString(ByteArray(32))
        assertNull(
            "ключ 32 байта не подходит для aes-128-gcm",
            LinkParser.parse("ss://2022-blake3-aes-128-gcm:${Uri.encode(key32)}@example.com:8388")
        )
    }

    @Test
    fun shadowsocksPluginsAreValidated() {
        assertNull(
            "obfs без mode ломает mihomo",
            LinkParser.parse("ss://aes-128-gcm:secret@example.com:8388?plugin=obfs-local%3Bobfs-host%3Dbing.com")
        )
        val v2ray = LinkParser.parse("ss://aes-128-gcm:secret@example.com:8388?plugin=v2ray-plugin")
        assertNotNull(v2ray)
        assertTrue(v2ray!!.raw.contains("\"mode\":\"websocket\""))
        assertNull(
            "v2ray-plugin mode=http не поддерживается",
            LinkParser.parse("ss://aes-128-gcm:secret@example.com:8388?plugin=v2ray-plugin%3Bmode%3Dhttp")
        )
        assertNull(
            "неизвестный плагин должен отбрасываться",
            LinkParser.parse("ss://aes-128-gcm:secret@example.com:8388?plugin=unknown-plugin")
        )
    }

    // ----------------------------------------------------------- Hysteria2

    @Test
    fun hysteria2ObfsIsValidated() {
        assertNull(
            "obfs=bogus ломает mihomo",
            LinkParser.parse("hysteria2://secret@example.com:443?obfs=bogus&obfs-password=x#H")
        )
        assertNull(
            "obfs без пароля ломает mihomo",
            LinkParser.parse("hysteria2://secret@example.com:443?obfs=salamander#H")
        )
        val node = LinkParser.parse(
            "hysteria2://secret@example.com:443?obfs=salamander&obfs-password=x" +
                "&upmbps=100&down=200#H"
        )
        assertNotNull(node)
        assertTrue(node!!.raw.contains("\"obfs\":\"salamander\""))
        assertTrue(node.raw.contains("\"up\":\"100 Mbps\""))
        assertTrue(node.raw.contains("\"down\":\"200 Mbps\""))
    }

    @Test
    fun hysteria2FingerprintIsValidated() {
        val bad = LinkParser.parse("hysteria2://secret@example.com:443?pinSHA256=abc#H")
        assertNotNull(bad)
        assertFalse(bad!!.raw.contains("\"fingerprint\""))

        val goodHex = "AB".repeat(32)
        val good = LinkParser.parse("hysteria2://secret@example.com:443?pinSHA256=$goodHex#H")
        assertNotNull(good)
        assertTrue(good!!.raw.contains(goodHex.lowercase()))
    }

    // ---------------------------------------------------------------- TUIC

    @Test
    fun tuicParsesRelayAndCongestion() {
        val node = LinkParser.parse(
            "tuic://$uuid:secret@example.com:443?sni=example.com" +
                "&udp_relay_mode=quic&congestion_control=cubic#TUIC"
        )
        assertNotNull(node)
        assertEquals("tuic", node!!.protocol)
        assertTrue(node.raw.contains("\"udp-relay-mode\":\"quic\""))
        assertTrue(node.raw.contains("\"congestion-controller\":\"cubic\""))
    }

    // -------------------------------------------------------------- AnyTLS

    @Test
    fun anytlsParses() {
        val node = LinkParser.parse("anytls://secret@example.com:443?sni=example.com#A")
        assertNotNull(node)
        assertEquals("anytls", node!!.protocol)
        assertEquals(443, node.port)
    }

    // ------------------------------------------------------------ WireGuard

    @Test
    fun wireguardParsesAndValidatesReserved() {
        val base = "wireguard://$wgPrivate@example.com:51820?publickey=$wgPublic&ip=10.0.0.2"
        val node = LinkParser.parse("$base&ipv6=fd00::2&reserved=1,2,3#WG")
        assertNotNull(node)
        assertEquals("wireguard", node!!.protocol)
        assertTrue(node.raw.contains("\"ip\":\"10.0.0.2\""))
        assertTrue(node.raw.contains("\"ipv6\":\"fd00::2\""))
        assertTrue(node.raw.contains("reserved"))

        val badReserved = LinkParser.parse("$base&reserved=1,2")
        assertNotNull(badReserved)
        assertFalse("reserved должен быть ровно из 3 байт", badReserved!!.raw.contains("\"reserved\""))

        assertNull(
            "без ip/ipv6 mihomo не запускается",
            LinkParser.parse("wireguard://$wgPrivate@example.com:51820?publickey=$wgPublic#WG")
        )
    }

    @Test
    fun wireguardPublicKeyKeepsPlusInQuery() {
        // '+' в query нельзя декодировать как пробел — иначе ключ превращается в мусор.
        val plusKey = Base64.getEncoder().encodeToString(
            ByteArray(32) { 0xFF.toByte() }.also { it[0] = 0xFB.toByte() }
        )
        assertTrue("тестовый ключ должен содержать '+'", plusKey.contains('+'))
        val node = LinkParser.parse(
            "wireguard://$wgPrivate@example.com:51820?publickey=$plusKey&ip=10.0.0.2#WG"
        )
        assertNotNull(node)
        // raw — это JSON, где org.json экранирует '/' как '\/', поэтому читаем поле.
        val storedKey = org.json.JSONObject(node!!.raw).getString("public-key")
        assertEquals("ключ должен остаться неизменным", plusKey, storedKey)
    }

    @Test
    fun queryKeysAreCaseInsensitive() {
        // Разные клиенты пишут publicKey/PublicKey/address в разном регистре.
        val node = LinkParser.parse(
            "wireguard://$wgPrivate@example.com:51820?PublicKey=$wgPublic&Address=10.9.8.7#WG"
        )
        assertNotNull("ключи query должны сравниваться без учёта регистра", node)
        val json = org.json.JSONObject(node!!.raw)
        assertEquals(wgPublic, json.getString("public-key"))
        assertEquals("10.9.8.7", json.getString("ip"))

        val hy2 = LinkParser.parse(
            "hysteria2://secret@example.com:443?PinSHA256=${"AB".repeat(32)}#H"
        )
        assertNotNull(hy2)
        assertTrue(hy2!!.raw.contains("\"fingerprint\""))
    }
}
