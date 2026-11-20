package app.rkn.vpn.core

import app.rkn.vpn.data.ProxyNode
import app.rkn.vpn.data.RoutingRule
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Генерация конфигурации mihomo.
 *
 * Mihomo принимает JSON (это подмножество YAML), поэтому конфиг собирается
 * через org.json — так не нужно вручную экранировать YAML.
 */
object ConfigBuilder {

    /** Ограничиваем число outbound'ов, чтобы конфиг не разрастался. */
    private const val MAX_HEALTH_NODES = 200

    data class Params(
        val mixedPort: Int,
        val apiPort: Int,
        val socksUser: String,
        val socksPassword: String,
        val apiSecret: String,
        val node: ProxyNode,
        val rules: List<RoutingRule>,
        val routingMode: String,
        val lanDirect: Boolean,
        /** Включённые пресеты разворачиваются в DOMAIN-SUFFIX правила. */
        val presets: Set<String> = emptySet(),
        // Остальные узлы добавляются в конфиг, чтобы API mihomo мог проверить
        // их реальную доступность, не переключая текущее подключение.
        val allNodes: List<ProxyNode> = emptyList()
    )

    /** Имена outbound'ов, по ним к ним обращается API mihomo. */
    fun proxyNames(nodes: List<ProxyNode>, selectedId: String?): Map<String, String> {
        val selected = nodes.firstOrNull { it.id == selectedId } ?: nodes.firstOrNull()
        val extras = nodes.filter { it.id != selected?.id }.take(MAX_HEALTH_NODES - 1)
        return buildMap {
            if (selected != null) put(selected.id, LinkParser.OUTBOUND_NAME)
            extras.forEachIndexed { index, node -> put(node.id, "rkn-$index") }
        }
    }

    fun build(params: Params): String {
        val proxies = JSONArray()
        proxies.put(JSONObject(params.node.raw).put("name", LinkParser.OUTBOUND_NAME))
        params.allNodes
            .filter { it.id != params.node.id }
            .take(MAX_HEALTH_NODES - 1)
            .forEachIndexed { index, node ->
                proxies.put(JSONObject(node.raw).put("name", "rkn-$index"))
            }

        val rules = JSONArray()
        if (params.routingMode != "global") {
            // Свои правила идут первыми и перекрывают пресеты: в Clash
            // побеждает первое совпадение.
            params.rules.forEach { rule ->
                if (isValidRule(rule)) rules.put(rule.toClashRule())
            }
            RoutingPresets.rulesFor(RoutingPresets.knownIds(params.presets)).forEach {
                rules.put(it.toClashRule())
            }
            if (params.lanDirect) {
                LAN_RULES.forEach { rules.put(it) }
            }
        }
        rules.put("MATCH,${LinkParser.OUTBOUND_NAME}")

        val root = JSONObject()
            .put("log-level", "warning")
            .put("ipv6", true)
            .put("allow-lan", false)
            .put("bind-address", "127.0.0.1")
            .put("mixed-port", params.mixedPort)
            .put("authentication", JSONArray().put("${params.socksUser}:${params.socksPassword}"))
            .put("mode", "rule")
            .put("unified-delay", true)
            .put("tcp-concurrent", true)
            .put("find-process-mode", "off")
            .put("geodata-mode", false)
            .put("geo-auto-update", false)
            .put("external-controller", "127.0.0.1:${params.apiPort}")
            .put("secret", params.apiSecret)
            .put(
                "profile",
                JSONObject()
                    .put("store-selected", false)
                    .put("store-fake-ip", false)
            )
            .put(
                "sniffer",
                JSONObject()
                    .put("enable", true)
                    .put("parse-pure-ip", true)
                    .put("override-destination", false)
                    .put(
                        "sniff",
                        JSONObject()
                            .put("TLS", JSONObject().put("ports", JSONArray().put(443).put(8443)))
                            .put("QUIC", JSONObject().put("ports", JSONArray().put(443).put(8443)))
                            .put("HTTP", JSONObject().put("ports", JSONArray().put(80).put(8080)))
                    )
            )
            // DNS ядра нужен, чтобы резолвить адреса самих прокси-серверов
            // внутри туннеля. Системные запросы по-прежнему отвечает hev mapdns
            // (fake-ip в 240.0.0.0/4), поэтому здесь НЕ включаем fake-ip: он
            // конфликтовал бы с диапазоном hev. DoH с IP-адресами bootstrap
            // не даёт утечки DNS мимо прокси и не требует системного резолвера.
            .put(
                "dns",
                JSONObject()
                    .put("enable", true)
                    .put("ipv6", true)
                    .put("enhanced-mode", "redir-host")
                    .put(
                        "nameserver",
                        JSONArray()
                            .put("https://cloudflare-dns.com/dns-query")
                            .put("https://dns.google/dns-query")
                    )
                    .put(
                        "proxy-server-nameserver",
                        JSONArray().put("https://cloudflare-dns.com/dns-query")
                    )
                    .put(
                        "bootstrap",
                        JSONArray().put("1.1.1.1").put("8.8.8.8").put("1.0.0.1")
                    )
            )
            .put("proxies", proxies)
            .put("rules", rules)

        return root.toYamlSafeJson()
    }

    /**
     * org.json на Android экранирует «/» как «\/». Для JSON это допустимо,
     * но mihomo разбирает конфиг YAML-парсером (JSON — подмножество YAML),
     * а в YAML такой escape-последовательности нет: ядро падает с
     * «found unknown escape character». Убираем только это экранирование —
     * остальные escape-последовательности org.json (\", \\, \n, \t, \uXXXX)
     * в YAML поддерживаются.
     */
    private fun JSONObject.toYamlSafeJson(): String = toString(2).replace("\\/", "/")

    private fun isValidRule(rule: RoutingRule): Boolean {
        val value = rule.value.trim()
        if (value.isEmpty() || value.contains(',') || value.contains('\n')) return false
        return when (rule.type) {
            "DOMAIN", "DOMAIN-SUFFIX", "DOMAIN-KEYWORD" ->
                value.none { it.isWhitespace() } && !value.startsWith(".") && !value.endsWith(".")
            "IP-CIDR" -> isValidCidr(value, 4)
            "IP-CIDR6" -> isValidCidr(value, 6)
            else -> false
        }
    }

    /**
     * mihomo не стартует при битом IP-CIDR (например, «10.0.0.0» без префикса
     * или «10.0.0.0/99»), поэтому правило проверяется до попадания в конфиг.
     */
    private fun isValidCidr(value: String, version: Int): Boolean {
        val parts = value.split('/')
        if (parts.size != 2) return false
        val maxPrefix = if (version == 4) 32 else 128
        if (parts[1].toIntOrNull()?.takeIf { it in 0..maxPrefix } == null) return false
        return isIpLiteral(parts[0], version)
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

    private val LAN_RULES = listOf(
        "IP-CIDR,127.0.0.0/8,DIRECT,no-resolve",
        "IP-CIDR,10.0.0.0/8,DIRECT,no-resolve",
        "IP-CIDR,172.16.0.0/12,DIRECT,no-resolve",
        "IP-CIDR,192.168.0.0/16,DIRECT,no-resolve",
        "IP-CIDR,169.254.0.0/16,DIRECT,no-resolve",
        "IP-CIDR6,fc00::/7,DIRECT,no-resolve",
        "IP-CIDR6,fe80::/10,DIRECT,no-resolve"
    )
}
