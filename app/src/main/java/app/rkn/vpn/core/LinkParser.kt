package app.rkn.vpn.core

import android.net.Uri
import android.util.Base64
import app.rkn.vpn.data.ProxyNode
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.UUID

/**
 * Разбор share-ссылок (vless/vmess/trojan/ss/hysteria2/tuic/anytls)
 * в outbound-объекты mihomo. Никаких внешних библиотек — только org.json.
 *
 * Всё, что не проходит проверку mihomo, отбрасывается здесь: иначе одна
 * битая ссылка в подписке роняет запуск ядра целиком.
 */
object LinkParser {

    private val supportedSchemes = listOf(
        "vless://", "vmess://", "trojan://", "ss://",
        "hysteria2://", "hy2://", "tuic://", "anytls://",
        "wireguard://", "wg://"
    )

    /** Методы, которые реально принимает mihomo v1.19.x. */
    private val SS_CIPHERS = setOf(
        "aes-128-gcm", "aes-192-gcm", "aes-256-gcm",
        "aes-128-cfb", "aes-192-cfb", "aes-256-cfb",
        "aes-128-ctr", "aes-192-ctr", "aes-256-ctr",
        "rc4-md5",
        "chacha20", "chacha20-ietf", "xchacha20",
        "chacha20-ietf-poly1305", "xchacha20-ietf-poly1305",
        "chacha8-ietf-poly1305", "xchacha8-ietf-poly1305",
        "2022-blake3-aes-128-gcm", "2022-blake3-aes-256-gcm",
        "2022-blake3-chacha20-poly1305",
        "none",
        "aes-128-gcm-siv", "aes-256-gcm-siv",
        "aes-128-ccm", "aes-192-ccm", "aes-256-ccm",
        "lea-128-gcm", "lea-192-gcm", "lea-256-gcm"
    )

    private val VMESS_CIPHERS = setOf("auto", "aes-128-gcm", "chacha20-poly1305", "none", "zero")

    private val XHTTP_MODES = setOf("auto", "packet-up", "stream-up", "stream-one")

    private val HY2_OBFS = setOf("salamander", "gecko")

    private val TUIC_RELAY_MODES = setOf("native", "quic")
    private val TUIC_CONGESTION = setOf("bbr", "cubic", "new_reno", "newreno")

    private val VLESS_FLOWS = setOf("xtls-rprx-vision", "xtls-rprx-vision-udp443")

    fun looksLikeLink(text: String): Boolean =
        supportedSchemes.any { text.trim().startsWith(it, ignoreCase = true) }

    /** Текст подписки (base64 или plain) -> список узлов. */
    fun parseSubscription(body: String): List<ProxyNode> {
        val text = if (body.contains("://")) body else decodeBase64(body) ?: body
        val result = LinkedHashMap<String, ProxyNode>()
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("//") }
            .forEach { line ->
                parse(line)?.let { node -> result[node.id] = node }
            }
        return result.values.toList()
    }

    fun parse(link: String): ProxyNode? = runCatching {
        when {
            link.startsWith("vless://", true) -> parseVless(link)
            link.startsWith("vmess://", true) -> parseVmess(link)
            link.startsWith("trojan://", true) -> parseTrojan(link)
            link.startsWith("ss://", true) -> parseSs(link)
            link.startsWith("hysteria2://", true) || link.startsWith("hy2://", true) ->
                parseHysteria2(link)
            link.startsWith("tuic://", true) -> parseTuic(link)
            link.startsWith("anytls://", true) -> parseAnytls(link)
            link.startsWith("wireguard://", true) || link.startsWith("wg://", true) ->
                parseWireguard(link)
            else -> null
        }
    }.getOrNull()

    // ---------------------------------------------------------------- VLESS

    private fun parseVless(link: String): ProxyNode? {
        val uri = Uri.parse(link)
        val server = uri.host?.takeIf { it.isNotBlank() } ?: return null
        val port = parseUriPort(uri.port, 443) ?: return null
        val uuid = uri.userInfo?.let { decodeComponent(it) }?.takeIf { it.isNotBlank() } ?: return null
        val q = Query(uri)
        val security = (q["security"] ?: "none").lowercase()
        val tls = security == "tls" || security == "reality" || security == "xtls"
        val network = (q["type"] ?: "tcp").lowercase()

        val proxy = JSONObject()
            .put("name", OUTBOUND_NAME)
            .put("type", "vless")
            .put("server", server)
            .put("port", port)
            .put("uuid", uuid)
            .put("udp", true)

        if (tls) {
            proxy.put("tls", true)
            (q["sni"] ?: q["host"] ?: q["peer"])?.let { proxy.put("servername", it) }
            q["alpn"]?.let { proxy.put("alpn", csv(it)) }
            (q["fp"] ?: q["client-fingerprint"])?.let { proxy.put("client-fingerprint", it) }
            if (q.bool("allowInsecure") || q.bool("allow_insecure") || q.bool("insecure")) {
                proxy.put("skip-cert-verify", true)
            }
            if (security == "reality") {
                val pbkRaw = q["pbk"] ?: q["publicKey"] ?: return null
                val pbk = normalizeRealityPublicKey(pbkRaw) ?: return null
                val reality = JSONObject().put("public-key", pbk)
                val sidRaw = q["sid"]
                if (!sidRaw.isNullOrEmpty()) {
                    val sid = normalizeRealityShortId(sidRaw) ?: return null
                    if (sid.isNotEmpty()) reality.put("short-id", sid)
                }
                proxy.put("reality-opts", reality)
            }
        }

        q["flow"]?.lowercase()?.takeIf { it in VLESS_FLOWS }?.let { proxy.put("flow", it) }

        // mihomo принимает только none/пусто или mlkem768x25519plus.<mode>.<rtt>.<keys>.
        // Всё остальное не передаём: иначе ядро не запустится.
        q["encryption"]?.takeIf { isValidVlessEncryption(it) }?.let { proxy.put("encryption", it) }

        applyTransport(proxy, network, q)
        return node(link, "vless", server, port, q.fragment, proxy)
    }

    // ---------------------------------------------------------------- VMess

    private fun parseVmess(link: String): ProxyNode? {
        val payload = link.substringAfter("://").substringBefore("#")
        val json = decodeBase64(payload) ?: return null
        val o = JSONObject(json)
        val server = o.optString("add").takeIf { it.isNotBlank() } ?: return null
        val portRaw = o.optString("port", "").trim()
        val port = if (portRaw.isEmpty()) 443 else portRaw.toIntOrNull()?.takeIf { it in 1..65535 }
            ?: return null
        val uuid = o.optString("id").takeIf { it.isNotBlank() } ?: return null
        val name = o.optString("ps")
        val tls = o.optString("tls").equals("tls", true)
        val net = o.optString("net", "tcp").lowercase()
        val host = o.optString("host")
        val path = o.optString("path")
        val sni = o.optString("sni")
        val fp = o.optString("fp")

        val cipher = o.optString("scy", "auto").trim().lowercase()
            .let { if (it in VMESS_CIPHERS) it else "auto" }

        val proxy = JSONObject()
            .put("name", OUTBOUND_NAME)
            .put("type", "vmess")
            .put("server", server)
            .put("port", port)
            .put("uuid", uuid)
            .put("alterId", o.optString("aid", "0").toIntOrNull() ?: 0)
            .put("cipher", cipher)
            .put("udp", true)

        if (tls) {
            proxy.put("tls", true)
            (sni.ifBlank { host }).takeIf { it.isNotBlank() }
                ?.let { proxy.put("servername", it) }
            o.optString("alpn").takeIf { it.isNotBlank() }?.let { proxy.put("alpn", csv(it)) }
            fp.takeIf { it.isNotBlank() }?.let { proxy.put("client-fingerprint", it) }
            if (o.optBoolean("allowInsecure") || o.optBoolean("skip-cert-verify")) {
                proxy.put("skip-cert-verify", true)
            }
        }

        when (net) {
            "ws" -> {
                proxy.put("network", "ws")
                val opts = JSONObject()
                path.takeIf { it.isNotBlank() }?.let { opts.put("path", it) }
                host.takeIf { it.isNotBlank() }
                    ?.let { opts.put("headers", JSONObject().put("Host", it)) }
                proxy.put("ws-opts", opts)
            }
            "httpupgrade" -> {
                // mihomo понимает httpupgrade только как ws + v2ray-http-upgrade.
                proxy.put("network", "ws")
                val opts = JSONObject().put("v2ray-http-upgrade", true)
                path.takeIf { it.isNotBlank() }?.let { opts.put("path", it) }
                host.takeIf { it.isNotBlank() }
                    ?.let { opts.put("headers", JSONObject().put("Host", it)) }
                proxy.put("ws-opts", opts)
            }
            "grpc" -> {
                proxy.put("network", "grpc")
                val service = path.removePrefix("/").ifBlank { "GunService" }
                proxy.put("grpc-opts", JSONObject().put("grpc-service-name", service))
            }
            "h2", "http" -> {
                proxy.put("network", "h2")
                val opts = JSONObject().put("path", path.ifBlank { "/" })
                if (host.isNotBlank()) opts.put("host", JSONArray().put(host))
                proxy.put("h2-opts", opts)
            }
            "quic" -> proxy.put("network", "quic")
            "kcp" -> proxy.put("network", "mkcp")
            else -> Unit
        }

        return node(link, "vmess", server, port, name, proxy)
    }

    // ---------------------------------------------------------------- Trojan

    private fun parseTrojan(link: String): ProxyNode? {
        val uri = Uri.parse(link)
        val server = uri.host?.takeIf { it.isNotBlank() } ?: return null
        val port = parseUriPort(uri.port, 443) ?: return null
        val password = uri.userInfo?.let { decodeComponent(it) }?.takeIf { it.isNotBlank() } ?: return null
        val q = Query(uri)
        val network = (q["type"] ?: "tcp").lowercase()

        val proxy = JSONObject()
            .put("name", OUTBOUND_NAME)
            .put("type", "trojan")
            .put("server", server)
            .put("port", port)
            .put("password", password)
            .put("udp", true)
            .put("sni", q["sni"] ?: q["peer"] ?: server)

        (q["fp"] ?: q["client-fingerprint"])?.let { proxy.put("client-fingerprint", it) }
        q["alpn"]?.let { proxy.put("alpn", csv(it)) }
        if (q.bool("allowInsecure") || q.bool("allow_insecure") || q.bool("insecure")) {
            proxy.put("skip-cert-verify", true)
        }
        applyTransport(proxy, network, q)
        return node(link, "trojan", server, port, q.fragment, proxy)
    }

    // ------------------------------------------------------------ Shadowsocks

    private fun parseSs(link: String): ProxyNode? {
        var body = link.substringAfter("://")
        val fragment = if (body.contains("#")) body.substringAfter("#") else ""
        body = body.substringBefore("#")
        val query = if (body.contains("?")) body.substringAfter("?") else ""
        body = body.substringBefore("?")

        var main = body
        if (!main.contains("@")) {
            val decoded = decodeBase64(main) ?: return null
            main = decoded
        }

        val at = main.lastIndexOf('@')
        if (at <= 0) return null
        var userInfo = main.substring(0, at)
        val hostPort = main.substring(at + 1)
        userInfo = if (userInfo.contains(":")) {
            decodeComponent(userInfo)
        } else {
            decodeBase64(userInfo) ?: return null
        }

        val rawMethod = userInfo.substringBefore(":", "")
        val password = userInfo.substringAfter(":", "")
        if (rawMethod.isBlank() || password.isBlank()) return null

        val method = normalizeSsMethod(rawMethod) ?: return null
        val normalizedPassword = normalizeSsPassword(method, password) ?: return null

        val (server, port) = splitHostPort(hostPort) ?: return null
        val proxy = JSONObject()
            .put("name", OUTBOUND_NAME)
            .put("type", "ss")
            .put("server", server)
            .put("port", port)
            .put("cipher", method)
            .put("password", normalizedPassword)
            .put("udp", true)

        if (query.isNotBlank()) {
            val pluginRaw = parseQueryString(query)
                .entries
                .firstOrNull { it.key.equals("plugin", ignoreCase = true) }
                ?.value
            if (!pluginRaw.isNullOrBlank() && !applySsPlugin(proxy, pluginRaw)) return null
        }

        return node(link, "ss", server, port, decodeComponent(fragment), proxy)
    }

    /** @return false, если плагин/режим не поддерживается mihomo. */
    private fun applySsPlugin(proxy: JSONObject, pluginRaw: String): Boolean {
        val parts = pluginRaw.split(";").filter { it.isNotBlank() }
        val plugin = parts.firstOrNull()?.trim()?.lowercase() ?: return false
        val opts = JSONObject()
        when (plugin) {
            "obfs-local", "simple-obfs" -> {
                var mode: String? = null
                parts.drop(1).forEach { p ->
                    val key = p.substringBefore("=").trim().lowercase()
                    val value = p.substringAfter("=", "").trim()
                    when (key) {
                        "obfs" -> mode = value.lowercase()
                        "obfs-host" -> if (value.isNotEmpty()) opts.put("host", value)
                    }
                }
                if (mode != "http" && mode != "tls") return false
                opts.put("mode", mode)
                proxy.put("plugin", "obfs")
                proxy.put("plugin-opts", opts)
            }
            "v2ray-plugin" -> {
                var mode = "websocket"
                parts.drop(1).forEach { p ->
                    val trimmed = p.trim()
                    if (trimmed.equals("tls", true)) {
                        opts.put("tls", true)
                        return@forEach
                    }
                    val key = trimmed.substringBefore("=").trim().lowercase()
                    val value = trimmed.substringAfter("=", "").trim()
                    when (key) {
                        "mode" -> mode = value.lowercase()
                        "host" -> if (value.isNotEmpty()) opts.put("host", value)
                        "path" -> if (value.isNotEmpty()) opts.put("path", value)
                    }
                }
                if (mode != "websocket") return false
                opts.put("mode", "websocket")
                proxy.put("plugin", "v2ray-plugin")
                proxy.put("plugin-opts", opts)
            }
            else -> return false
        }
        return true
    }

    private fun normalizeSsMethod(raw: String): String? {
        var method = raw.trim().lowercase()
        if (method.startsWith("aead_")) method = method.removePrefix("aead_")
        method = method.replace('_', '-')
        method = when (method) {
            "chacha20-poly1305" -> "chacha20-ietf-poly1305"
            "xchacha20-poly1305" -> "xchacha20-ietf-poly1305"
            else -> method
        }
        return method.takeIf { it in SS_CIPHERS }
    }

    private fun normalizeSsPassword(method: String, password: String): String? {
        if (!method.startsWith("2022-blake3-")) return password
        val expected = if (method == "2022-blake3-aes-128-gcm") 16 else 32
        val bytes = decodeKeyBytes(password, expected) ?: return null
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    // -------------------------------------------------------------- Hysteria2

    private fun parseHysteria2(link: String): ProxyNode? {
        val uri = Uri.parse(link)
        val server = uri.host?.takeIf { it.isNotBlank() } ?: return null
        val port = parseUriPort(uri.port, 443) ?: return null
        val password = uri.userInfo?.let { decodeComponent(it) }?.takeIf { it.isNotBlank() } ?: return null
        val q = Query(uri)

        val proxy = JSONObject()
            .put("name", OUTBOUND_NAME)
            .put("type", "hysteria2")
            .put("server", server)
            .put("port", port)
            .put("password", password)

        (q["sni"] ?: q["peer"])?.let { proxy.put("sni", it) }
        if (q.bool("insecure") || q.bool("allowInsecure") || q.bool("allow_insecure")) {
            proxy.put("skip-cert-verify", true)
        }

        val obfs = q["obfs"]?.lowercase()
        if (obfs != null) {
            val obfsPassword = q["obfs-password"] ?: q["obfs_password"]
            if (obfs !in HY2_OBFS || obfsPassword.isNullOrBlank()) return null
            proxy.put("obfs", obfs)
            proxy.put("obfs-password", obfsPassword)
        }

        val fingerprint = q["pinSHA256"] ?: q["pin-sha256"] ?: q["fingerprint"]
        normalizeSha256Fingerprint(fingerprint)?.let { proxy.put("fingerprint", it) }

        q["alpn"]?.let { proxy.put("alpn", csv(it)) }
        parseBandwidth(q["upmbps"], q["up"])?.let { proxy.put("up", "$it Mbps") }
        parseBandwidth(q["downmbps"], q["down"])?.let { proxy.put("down", "$it Mbps") }

        return node(link, "hysteria2", server, port, q.fragment, proxy)
    }

    // ------------------------------------------------------------------- TUIC

    private fun parseTuic(link: String): ProxyNode? {
        val uri = Uri.parse(link)
        val server = uri.host?.takeIf { it.isNotBlank() } ?: return null
        val port = parseUriPort(uri.port, 443) ?: return null
        val userInfo = uri.userInfo?.let { decodeComponent(it) } ?: return null
        val uuid = userInfo.substringBefore(":")
        val password = userInfo.substringAfter(":", "")
        if (uuid.isBlank() || password.isBlank()) return null
        val q = Query(uri)

        val relayMode = (q["udp_relay_mode"] ?: q["udp-relay-mode"] ?: "native").lowercase()
            .let { if (it in TUIC_RELAY_MODES) it else "native" }
        val congestion = (q["congestion_control"] ?: q["congestion-controller"] ?: "bbr").lowercase()
            .let { if (it in TUIC_CONGESTION) it else "bbr" }

        val proxy = JSONObject()
            .put("name", OUTBOUND_NAME)
            .put("type", "tuic")
            .put("server", server)
            .put("port", port)
            .put("uuid", uuid)
            .put("password", password)
            .put("udp-relay-mode", relayMode)
            .put("congestion-controller", congestion)
            .put("alpn", q["alpn"]?.let { csv(it) } ?: JSONArray().put("h3"))

        (q["sni"] ?: q["peer"])?.let { proxy.put("sni", it) }
        if (q.bool("allow_insecure") || q.bool("insecure") || q.bool("allowInsecure")) {
            proxy.put("skip-cert-verify", true)
        }
        if (q.bool("disable_sni") || q.bool("disable-sni")) proxy.put("disable-sni", true)

        return node(link, "tuic", server, port, q.fragment, proxy)
    }

    // --------------------------------------------------------------- AnyTLS

    private fun parseAnytls(link: String): ProxyNode? {
        val uri = Uri.parse(link)
        val server = uri.host?.takeIf { it.isNotBlank() } ?: return null
        val port = parseUriPort(uri.port, 443) ?: return null
        val password = uri.userInfo?.let { decodeComponent(it) }?.takeIf { it.isNotBlank() } ?: return null
        val q = Query(uri)

        val proxy = JSONObject()
            .put("name", OUTBOUND_NAME)
            .put("type", "anytls")
            .put("server", server)
            .put("port", port)
            .put("password", password)
            .put("udp", true)

        (q["sni"] ?: q["peer"])?.let { proxy.put("sni", it) }
        (q["fp"] ?: q["client-fingerprint"])?.let { proxy.put("client-fingerprint", it) }
        if (q.bool("insecure") || q.bool("allowInsecure") || q.bool("allow_insecure")) {
            proxy.put("skip-cert-verify", true)
        }
        return node(link, "anytls", server, port, q.fragment, proxy)
    }

    // ------------------------------------------------------------- WireGuard

    private fun parseWireguard(link: String): ProxyNode? {
        val uri = Uri.parse(link)
        val server = uri.host?.takeIf { it.isNotBlank() } ?: return null
        val port = parseUriPort(uri.port, 51820) ?: return null
        val privateKeyRaw = uri.userInfo?.let { decodeComponent(it) }?.takeIf { it.isNotBlank() }
            ?: return null
        val privateKey = normalizeWireguardKey(privateKeyRaw) ?: return null
        val q = Query(uri)
        val publicKeyRaw = q["publickey"] ?: q["public-key"] ?: q["pk"] ?: return null
        val publicKey = normalizeWireguardKey(publicKeyRaw) ?: return null

        var ip: String? = null
        var ipv6: String? = null
        (q["address"] ?: q["ip"])?.split(",")?.forEach { part ->
            val clean = part.trim().substringBefore("/").trim()
            if (clean.isEmpty()) return@forEach
            if (clean.contains(':')) {
                if (ipv6 == null && isIpLiteral(clean, 6)) ipv6 = clean
            } else if (ip == null && isIpLiteral(clean, 4)) {
                ip = clean
            }
        }
        q["ipv6"]?.substringBefore("/")?.trim()
            ?.takeIf { it.isNotEmpty() && isIpLiteral(it, 6) }
            ?.let { ipv6 = it }

        if (ip == null && ipv6 == null) return null

        val proxy = JSONObject()
            .put("name", OUTBOUND_NAME)
            .put("type", "wireguard")
            .put("server", server)
            .put("port", port)
            .put("private-key", privateKey)
            .put("public-key", publicKey)
            .put("udp", true)
            .put("allowed-ips", JSONArray().put("0.0.0.0/0").put("::/0"))
        ip?.let { proxy.put("ip", it) }
        ipv6?.let { proxy.put("ipv6", it) }

        (q["presharedkey"] ?: q["preshared-key"] ?: q["psk"])
            ?.let { normalizeWireguardKey(it) }
            ?.let { proxy.put("pre-shared-key", it) }

        q["mtu"]?.toIntOrNull()?.takeIf { it in 576..9000 }?.let { proxy.put("mtu", it) }

        q["reserved"]?.let { raw ->
            val ints = raw.split(",").map { it.trim().toIntOrNull() }
            if (ints.size == 3) {
                val a = ints[0]
                val b = ints[1]
                val c = ints[2]
                if (a != null && b != null && c != null &&
                    a in 0..255 && b in 0..255 && c in 0..255
                ) {
                    proxy.put("reserved", JSONArray().put(a).put(b).put(c))
                }
            }
        }

        return node(link, "wireguard", server, port, q.fragment, proxy)
    }

    private fun normalizeWireguardKey(value: String): String? {
        val bytes = decodeKeyBytes(value, 32) ?: return null
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    // -------------------------------------------------------------- transport

    private fun applyTransport(proxy: JSONObject, network: String, q: Query) {
        when (network) {
            "ws" -> {
                proxy.put("network", "ws")
                val opts = JSONObject()
                q["path"]?.let { opts.put("path", it) }
                (q["host"] ?: q["sni"])?.let {
                    opts.put("headers", JSONObject().put("Host", it))
                }
                q["ed"]?.toIntOrNull()?.let { opts.put("max-early-data", it) }
                q["eh"]?.let { opts.put("early-data-header-name", it) }
                proxy.put("ws-opts", opts)
            }
            "httpupgrade" -> {
                proxy.put("network", "ws")
                val opts = JSONObject().put("v2ray-http-upgrade", true)
                q["path"]?.let { opts.put("path", it) }
                (q["host"] ?: q["sni"])?.let {
                    opts.put("headers", JSONObject().put("Host", it))
                }
                proxy.put("ws-opts", opts)
            }
            "grpc" -> {
                proxy.put("network", "grpc")
                val service = (q["serviceName"] ?: q["path"]?.removePrefix("/")).orEmpty()
                proxy.put(
                    "grpc-opts",
                    JSONObject().put("grpc-service-name", service.ifBlank { "GunService" })
                )
            }
            "h2", "http" -> {
                proxy.put("network", "h2")
                val opts = JSONObject().put("path", q["path"] ?: "/")
                (q["host"] ?: q["sni"])?.let { opts.put("host", JSONArray().put(it)) }
                proxy.put("h2-opts", opts)
            }
            "xhttp" -> {
                proxy.put("network", "xhttp")
                if (!proxy.has("alpn")) {
                    proxy.put("alpn", q["alpn"]?.let { csv(it) } ?: JSONArray().put("h2"))
                }
                proxy.put("xhttp-opts", buildXhttpOpts(q))
            }
            "quic" -> proxy.put("network", "quic")
            "kcp" -> proxy.put("network", "mkcp")
            else -> Unit
        }
    }

    private fun buildXhttpOpts(q: Query): JSONObject {
        val opts = JSONObject()
        opts.put("path", q["path"] ?: "/")
        (q["host"] ?: q["sni"])?.let { opts.put("host", it) }
        q["mode"]?.lowercase()?.takeIf { it in XHTTP_MODES }?.let { opts.put("mode", it) }

        val extra = q["extra"]?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (extra != null) {
            extra.optString("mode").lowercase().takeIf { it in XHTTP_MODES }?.let {
                if (!opts.has("mode")) opts.put("mode", it)
            }
            extra.optString("xPaddingBytes").takeIf { it.isNotBlank() }
                ?.let { opts.put("x-padding-bytes", it) }
            if (extra.optBoolean("noGRPCHeader")) opts.put("no-grpc-header", true)
            extra.optString("scMaxEachPostBytes").toIntOrNull()?.takeIf { it > 0 }
                ?.let { opts.put("sc-max-each-post-bytes", it) }
            extra.optString("scMinPostsIntervalMs").toIntOrNull()?.takeIf { it > 0 }
                ?.let { opts.put("sc-min-posts-interval-ms", it) }

            val xmux = extra.optJSONObject("xmux")
            if (xmux != null) {
                val reuse = JSONObject()
                listOf(
                    "maxConcurrency" to "max-concurrency",
                    "maxConnections" to "max-connections",
                    "cMaxReuseTimes" to "c-max-reuse-times",
                    "hMaxRequestTimes" to "h-max-request-times",
                    "hMaxReusableSecs" to "h-max-reusable-secs",
                    "hKeepAlivePeriod" to "h-keep-alive-period"
                ).forEach { (src, dst) ->
                    xmux.optString(src).takeIf { it.isNotBlank() }?.let { reuse.put(dst, it) }
                }
                if (reuse.length() > 0) opts.put("reuse-settings", reuse)
            }
        }
        return opts
    }

    // ----------------------------------------------------------------- utils

    private fun node(
        link: String,
        protocol: String,
        server: String,
        port: Int,
        name: String,
        proxy: JSONObject
    ): ProxyNode {
        val display = sanitizeName(name.ifBlank { "$server:$port" })
        val id = UUID.nameUUIDFromBytes(link.toByteArray(Charsets.UTF_8)).toString()
        return ProxyNode(
            id = id,
            name = display,
            protocol = protocol,
            server = server,
            port = port,
            raw = proxy.toString()
        )
    }

    fun sanitizeName(name: String): String {
        val cleaned = name
            .replace('\n', ' ')
            .replace('\r', ' ')
            .replace(',', ' ')
            .trim()
        return if (cleaned.length > 64) cleaned.take(64) else cleaned.ifBlank { "node" }
    }

    private fun csv(value: String): JSONArray {
        val arr = JSONArray()
        value.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { arr.put(it) }
        return arr
    }

    private fun parseUriPort(raw: Int, default: Int): Int? = when {
        raw == -1 -> default
        raw in 1..65535 -> raw
        else -> null
    }

    private fun splitHostPort(value: String): Pair<String, Int>? {
        val v = value.trim()
        if (v.startsWith("[")) {
            val end = v.indexOf(']')
            if (end < 0) return null
            val host = v.substring(1, end).takeIf { it.isNotBlank() } ?: return null
            val port = v.substringAfter("]:", "").toIntOrNull()?.takeIf { it in 1..65535 }
                ?: return null
            return host to port
        }
        val idx = v.lastIndexOf(':')
        if (idx <= 0) return null
        val host = v.substring(0, idx).takeIf { it.isNotBlank() } ?: return null
        val port = v.substring(idx + 1).toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        return host to port
    }

    private fun isValidVlessEncryption(value: String): Boolean {
        if (value.equals("none", true)) return false
        val parts = value.split(".")
        if (parts.size < 4 || parts[0] != "mlkem768x25519plus") return false
        if (parts[1] !in setOf("native", "xorpub", "random")) return false
        if (parts[2] !in setOf("1rtt", "0rtt")) return false
        return parts.drop(3).all { part ->
            // Короткие части — это padding, а не ключи.
            if (part.length < 20) {
                true
            } else {
                val bytes = runCatching {
                    Base64.decode(
                        part,
                        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
                    )
                }.getOrNull()
                bytes != null && (bytes.size == 32 || bytes.size == 1184)
            }
        }
    }

    private fun normalizeRealityPublicKey(value: String): String? {
        val normalized = value.trim().trimEnd('=').replace('+', '-').replace('/', '_')
        if (normalized.isEmpty()) return null
        val bytes = runCatching {
            Base64.decode(normalized, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        }.getOrNull() ?: return null
        if (bytes.size != 32) return null
        val canonical = Base64.encodeToString(
            bytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        return canonical.takeIf { it == normalized }
    }

    private fun normalizeRealityShortId(value: String): String? {
        val sid = value.trim()
        if (sid.isEmpty()) return ""
        if (sid.length > 16 || sid.length % 2 != 0) return null
        if (!sid.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) return null
        return sid.lowercase()
    }

    private fun normalizeSha256Fingerprint(value: String?): String? {
        val hex = value?.trim()?.replace(":", "")?.replace(" ", "")?.lowercase() ?: return null
        if (hex.length != 64) return null
        if (!hex.all { it in '0'..'9' || it in 'a'..'f' }) return null
        return hex
    }

    private fun parseBandwidth(vararg values: String?): Int? {
        values.forEach { value ->
            if (value.isNullOrBlank()) return@forEach
            val digits = value.trim().takeWhile { it.isDigit() }
            val number = digits.toIntOrNull() ?: return@forEach
            if (number > 0) return number
        }
        return null
    }

    private fun isIpLiteral(value: String, version: Int): Boolean {
        val v = value.trim()
        if (v.isEmpty() || v.contains('/')) return false
        if (version == 4) {
            if (!v.matches(Regex("^\\d{1,3}(\\.\\d{1,3}){3}$"))) return false
            if (v.split('.').any { (it.toIntOrNull() ?: 256) !in 0..255 }) return false
            return runCatching { InetAddress.getByName(v) is Inet4Address }.getOrDefault(false)
        }
        if (!v.matches(Regex("^[0-9a-fA-F:.]+$"))) return false
        return runCatching { InetAddress.getByName(v) is Inet6Address }.getOrDefault(false)
    }

    private fun decodeKeyBytes(value: String, expected: Int): ByteArray? {
        val cleaned = value.trim()
        if (cleaned.isEmpty()) return null
        val hasUrlSafe = cleaned.any { it == '-' || it == '_' }
        val hasStandard = cleaned.any { it == '+' || it == '/' }
        val alphabets = when {
            hasUrlSafe && !hasStandard -> listOf(Base64.URL_SAFE)
            hasStandard && !hasUrlSafe -> listOf(0)
            else -> listOf(Base64.URL_SAFE, 0)
        }
        for (alphabet in alphabets) {
            for (noPadding in listOf(true, false)) {
                val flags = alphabet or Base64.NO_WRAP or
                    (if (noPadding) Base64.NO_PADDING else 0)
                val bytes = runCatching { Base64.decode(cleaned, flags) }.getOrNull() ?: continue
                if (bytes.size == expected) return bytes
            }
        }
        return null
    }

    fun decodeBase64(input: String): String? {
        val cleaned = input.trim().replace("\n", "").replace("\r", "").replace(" ", "")
        if (cleaned.isEmpty()) return null

        // Android-декодер не сообщает об ошибке, если в URL-safe тексте
        // встречается символ стандартного алфавита ('/' или '+'): он просто
        // пропускает его и возвращает мусор со сдвигом. Поэтому алфавит
        // выбираем по фактическим символам, а результат проверяем round-trip'ом.
        val hasUrlSafe = cleaned.any { it == '-' || it == '_' }
        val hasStandard = cleaned.any { it == '+' || it == '/' }
        val alphabets = when {
            hasUrlSafe && !hasStandard -> listOf(Base64.URL_SAFE)
            hasStandard && !hasUrlSafe -> listOf(0)
            else -> listOf(Base64.URL_SAFE, 0)
        }
        val paddings = listOf(
            Base64.NO_WRAP or Base64.NO_PADDING,
            Base64.NO_WRAP,
            Base64.DEFAULT
        )

        for (alphabet in alphabets) {
            for (padding in paddings) {
                val flags = alphabet or padding
                val bytes = runCatching { Base64.decode(cleaned, flags) }.getOrNull() ?: continue
                if (bytes.isEmpty()) continue
                val text = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull() ?: continue
                if (text.contains('\uFFFD')) continue
                if (!base64RoundTrips(cleaned, bytes, flags)) continue
                return text
            }
        }
        return null
    }

    /** Проверка, что байты кодируются обратно в тот же base64 (без padding). */
    private fun base64RoundTrips(input: String, bytes: ByteArray, flags: Int): Boolean {
        val encoded = runCatching {
            Base64.encodeToString(bytes, (flags and Base64.URL_SAFE) or Base64.NO_WRAP)
        }.getOrNull() ?: return false
        return encoded.trimEnd('=') == input.trimEnd('=')
    }

    private fun decodeComponent(value: String): String =
        runCatching { Uri.decode(value) }.getOrDefault(value)

    /**
     * Разбор query вручную через Uri.decode, а не getQueryParameter:
     * последний превращает '+' в пробел и ломает base64-ключи WireGuard.
     */
    private fun parseQueryString(encoded: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        encoded.split("&").forEach { pair ->
            if (pair.isEmpty()) return@forEach
            val index = pair.indexOf('=')
            val key = decodeComponent(if (index >= 0) pair.substring(0, index) else pair)
            val value = decodeComponent(if (index >= 0) pair.substring(index + 1) else "")
            if (key.isNotEmpty() && value.isNotEmpty()) result.putIfAbsent(key, value)
        }
        return result
    }

    private class Query(private val uri: Uri) {
        // Ключи query в share-ссылках встречаются в разном регистре
        // (publicKey/publickey, pinSHA256/pin-sha256), поэтому сравниваем без учёта регистра.
        private val values: Map<String, String> =
            parseQueryString(uri.encodedQuery ?: "").mapKeys { it.key.lowercase() }
        val fragment: String = uri.fragment ?: ""

        operator fun get(key: String): String? =
            values[key.lowercase()]?.takeIf { it.isNotBlank() }

        fun bool(key: String): Boolean {
            val v = values[key.lowercase()] ?: return false
            return v == "1" || v.equals("true", true)
        }
    }

    const val OUTBOUND_NAME = "PROXY"
}
