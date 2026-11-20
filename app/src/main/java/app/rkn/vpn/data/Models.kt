package app.rkn.vpn.data

import app.rkn.vpn.util.SystemKillSwitch
import org.json.JSONArray
import org.json.JSONObject

/** Куда направлять трафик, подходящий под правило. */
enum class RuleTarget { DIRECT, PROXY }

/** Пользовательское правило маршрутизации. */
data class RoutingRule(
    val id: String,
    val type: String,
    val value: String,
    val target: RuleTarget
) {
    /**
     * `no-resolve` для IP-правил обязателен: системные DNS-запросы внутри
     * туннеля отдаёт hev mapdns (fake-ip в 240.0.0.0/4), а DNS mihomo
     * включён только для внутренних запросов и адресов прокси-серверов.
     * Без no-resolve mihomo попытался бы резолвить домен ещё раз — лишняя
     * задержка на каждое соединение и запрос мимо выбранного DoH.
     */
    fun toClashRule(): String {
        val suffix = if (type == "IP-CIDR" || type == "IP-CIDR6") ",no-resolve" else ""
        return "$type,$value,${target.name}$suffix"
    }
}

/** Один узел из подписки. raw — готовый JSON-объект outbound'а для mihomo. */
data class ProxyNode(
    val id: String,
    val name: String,
    val protocol: String,
    val server: String,
    val port: Int,
    val raw: String
)

/** Данные подписки Remnawave (заголовок subscription-userinfo и profile-*). */
data class SubscriptionInfo(
    val title: String = "",
    val upload: Long = 0L,
    val download: Long = 0L,
    val total: Long = 0L,
    val expire: Long = 0L,
    val updatedAt: Long = 0L,
    val webPageUrl: String = "",
    val updateIntervalHours: Int = 12,
    val hwidLimitReached: Boolean = false,
    val hwidNotSupported: Boolean = false
) {
    val used: Long get() = upload + download
    val remaining: Long get() = if (total > 0) (total - used).coerceAtLeast(0) else 0L
    val hasTraffic: Boolean get() = total > 0
    val hasExpire: Boolean get() = expire > 0
}

/** Единое состояние приложения для UI. */
data class AppState(
    val connected: Boolean = false,
    val connecting: Boolean = false,
    /** Ядро/hev перезапускаются без закрытия TUN: трафик пока теряется, а не уходит напрямую. */
    val reconnecting: Boolean = false,
    /** Результат проверки выбранного узла через API ядра: null — ещё не проверяли. */
    val nodeReachable: Boolean? = null,
    val error: String? = null,
    val startedAt: Long = 0L,
    val upload: Long = 0L,
    val download: Long = 0L,
    val upSpeed: Long = 0L,
    val downSpeed: Long = 0L,
    val nodes: List<ProxyNode> = emptyList(),
    val selectedId: String? = null,
    val subscription: SubscriptionInfo = SubscriptionInfo(),
    val subscriptionUrl: String = "",
    val rules: List<RoutingRule> = emptyList(),
    val latencies: Map<String, Int?> = emptyMap(),
    val healthLatencies: Map<String, Int?> = emptyMap(),
    val healthChecking: Boolean = false,
    val healthAvailable: Boolean = false,
    val refreshing: Boolean = false,
    val toast: String? = null,
    val corePresent: Boolean = false,
    val routingMode: String = "rules",
    val lanDirect: Boolean = true,
    val activePresets: Set<String> = emptySet(),
    val splitEnabled: Boolean = false,
    val splitMode: String = "include",
    val splitApps: Set<String> = emptySet(),
    val checkUrl: String = Prefs.DEFAULT_CHECK_URL,
    val dnsMode: String = Prefs.DNS_AUTO,
    val dnsCustom: String = "",
    val autoSwitch: Boolean = true,
    val showKillSwitchStatus: Boolean = true,
    val killSwitchStatus: SystemKillSwitch.Status = SystemKillSwitch.Status.UNKNOWN,
    val theme: String = "system",
    val language: String = "system"
) {
    val selectedNode: ProxyNode? get() = nodes.firstOrNull { it.id == selectedId }

    /** Результат проверки через mihomo важнее TCP-пинга, если он уже есть. */
    val effectiveLatencies: Map<String, Int?>
        get() = healthLatencies.ifEmpty { latencies }
}

// ---------- JSON (org.json, без внешних зависимостей) ----------

fun nodesToJson(nodes: List<ProxyNode>): String {
    val arr = JSONArray()
    nodes.forEach { n ->
        arr.put(
            JSONObject()
                .put("id", n.id)
                .put("name", n.name)
                .put("protocol", n.protocol)
                .put("server", n.server)
                .put("port", n.port)
                .put("raw", n.raw)
        )
    }
    return arr.toString()
}

fun nodesFromJson(value: String?): List<ProxyNode> {
    if (value.isNullOrBlank()) return emptyList()
    return runCatching {
        val arr = JSONArray(value)
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id")
                val raw = o.optString("raw")
                if (id.isBlank() || raw.isBlank()) continue
                add(
                    ProxyNode(
                        id = id,
                        name = o.optString("name", "node"),
                        protocol = o.optString("protocol", "unknown"),
                        server = o.optString("server"),
                        port = o.optInt("port", 443),
                        raw = raw
                    )
                )
            }
        }
    }.getOrDefault(emptyList())
}

fun rulesToJson(rules: List<RoutingRule>): String {
    val arr = JSONArray()
    rules.forEach { r ->
        arr.put(
            JSONObject()
                .put("id", r.id)
                .put("type", r.type)
                .put("value", r.value)
                .put("target", r.target.name)
        )
    }
    return arr.toString()
}

fun rulesFromJson(value: String?): List<RoutingRule> {
    if (value.isNullOrBlank()) return emptyList()
    return runCatching {
        val arr = JSONArray(value)
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val valueText = o.optString("value")
                if (valueText.isBlank()) continue
                add(
                    RoutingRule(
                        id = o.optString("id", "rule-$i"),
                        type = o.optString("type", "DOMAIN-SUFFIX"),
                        value = valueText,
                        target = runCatching {
                            RuleTarget.valueOf(o.optString("target", "DIRECT"))
                        }.getOrDefault(RuleTarget.DIRECT)
                    )
                )
            }
        }
    }.getOrDefault(emptyList())
}

fun subscriptionToJson(info: SubscriptionInfo): String = JSONObject()
    .put("title", info.title)
    .put("upload", info.upload)
    .put("download", info.download)
    .put("total", info.total)
    .put("expire", info.expire)
    .put("updatedAt", info.updatedAt)
    .put("webPageUrl", info.webPageUrl)
    .put("updateIntervalHours", info.updateIntervalHours)
    .put("hwidLimitReached", info.hwidLimitReached)
    .put("hwidNotSupported", info.hwidNotSupported)
    .toString()

fun subscriptionFromJson(value: String?): SubscriptionInfo {
    if (value.isNullOrBlank()) return SubscriptionInfo()
    return runCatching {
        val o = JSONObject(value)
        SubscriptionInfo(
            title = o.optString("title"),
            upload = o.optLong("upload"),
            download = o.optLong("download"),
            total = o.optLong("total"),
            expire = o.optLong("expire"),
            updatedAt = o.optLong("updatedAt"),
            webPageUrl = o.optString("webPageUrl"),
            updateIntervalHours = o.optInt("updateIntervalHours", 12),
            hwidLimitReached = o.optBoolean("hwidLimitReached"),
            hwidNotSupported = o.optBoolean("hwidNotSupported")
        )
    }.getOrDefault(SubscriptionInfo())
}
