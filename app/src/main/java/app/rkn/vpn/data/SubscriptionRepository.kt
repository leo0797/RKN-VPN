package app.rkn.vpn.data

import android.os.Build
import app.rkn.vpn.R
import app.rkn.vpn.core.ConfigImporter
import app.rkn.vpn.core.LinkParser
import app.rkn.vpn.core.LocalProxy
import app.rkn.vpn.core.Strings
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Загрузка подписки Remnawave.
 *
 * Поддерживаются base64- и plain-списки share-ссылок, Clash YAML и SIP008 JSON,
 * а также обёртки панелей вида {"links": [...]} / [ {"url": "..."} ].
 * Заголовки subscription-userinfo / profile-* дают трафик, срок и название.
 */
class SubscriptionRepository(
    private val prefs: Prefs,
    private val strings: Strings
) {

    data class Result(
        val nodes: List<ProxyNode>,
        val info: SubscriptionInfo
    )

    /**
     * @param proxy локальный mixed-port ядра; используется, если прямой запрос
     * не прошёл из-за блокировки сети (но не при неверной ссылке).
     */
    fun fetch(url: String, proxy: LocalProxy? = null): Result {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) error(strings.get(R.string.error_subscription_url_required))

        val response = request(trimmed, proxy)
        if (response.code !in 200..299) {
            val hint = when (response.code) {
                404 -> strings.get(R.string.error_sub_not_found)
                401, 403 -> strings.get(R.string.error_sub_forbidden, response.code)
                else -> strings.get(R.string.error_sub_http_status, response.code)
            }
            error(hint)
        }

        val nodes = extractNodes(response.body)
        if (nodes.isEmpty()) error(strings.get(R.string.error_sub_no_nodes))

        return Result(nodes, parseInfo(response.headers))
    }

    // ------------------------------------------------------------- transport

    private data class Response(
        val code: Int,
        val body: String,
        val headers: Map<String, String>
    )

    /** Сначала напрямую, затем — при сетевой ошибке — через локальный прокси. */
    private fun request(url: String, proxy: LocalProxy?): Response {
        val direct = runCatching { requestOnce(url, null) }
        if (direct.isSuccess) return direct.getOrThrow()
        val directError = direct.exceptionOrNull()!!
        // Неверную схему/адрес повторять бессмысленно и долго.
        if (proxy == null || directError is IllegalArgumentException) throw directError

        return runCatching { requestOnce(url, proxy) }.getOrElse { proxyError ->
            throw IllegalStateException(
                strings.get(
                    R.string.error_sub_failed_both,
                    directError.message.orEmpty(),
                    proxyError.message.orEmpty()
                ),
                proxyError
            )
        }
    }

    /** Одна попытка с ручными редиректами. Раньше здесь было 6 попыток без пауз. */
    private fun requestOnce(url: String, proxy: LocalProxy?): Response {
        var current = url
        var redirects = 0
        while (true) {
            val parsed = runCatching { URL(current) }.getOrElse {
                throw IllegalArgumentException(strings.get(R.string.error_sub_invalid_url))
            }
            // Только http/https: file:, jar: и прочие схемы не открываем.
            if (parsed.protocol?.lowercase() !in ALLOWED_SCHEMES) {
                throw IllegalArgumentException(strings.get(R.string.error_sub_scheme))
            }

            val conn = if (proxy != null) {
                proxy.open(parsed)
            } else {
                parsed.openConnection() as HttpURLConnection
            }
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.instanceFollowRedirects = false
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "*/*")
            conn.setRequestProperty("Accept-Encoding", "identity")
            conn.setRequestProperty("x-hwid", prefs.hwid)
            conn.setRequestProperty("x-device-os", "Android")
            conn.setRequestProperty("x-ver-os", Build.VERSION.RELEASE ?: "unknown")
            conn.setRequestProperty(
                "x-device-model",
                listOf(Build.MANUFACTURER, Build.MODEL)
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
                    .trim()
            )

            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location")
                    if (location.isNullOrBlank()) {
                        throw IllegalStateException(strings.get(R.string.error_sub_redirect))
                    }
                    if (++redirects > MAX_REDIRECTS) {
                        throw IllegalStateException(strings.get(R.string.error_sub_redirect_loop))
                    }
                    val next = URL(URL(current), location)
                    // Редирект https -> http отдает токен подписки и HWID в
                    // открытом виде, даже если исходная ссылка была защищена.
                    if (isDowngrade(current, next.toString())) {
                        throw IllegalStateException(strings.get(R.string.error_sub_downgrade))
                    }
                    current = next.toString()
                    continue
                }

                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.let { input ->
                    val decoded = if (conn.getHeaderField("Content-Encoding")
                            ?.contains("gzip", ignoreCase = true) == true
                    ) GZIPInputStream(input) else input
                    decoded.use { readAll(it) }
                }.orEmpty()

                val headers = buildMap {
                    conn.headerFields.forEach { (key, values) ->
                        if (key != null && values != null) {
                            put(key.lowercase(), values.joinToString(", "))
                        }
                    }
                }
                return Response(code, body, headers)
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun readAll(stream: InputStream): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = stream.read(buffer)
            if (read <= 0) break
            total += read
            if (total > MAX_BODY_BYTES) {
                error(strings.get(R.string.error_sub_too_large))
            }
            out.write(buffer, 0, read)
        }
        // Некоторые панели отдают подписку с UTF-8 BOM — он ломает и base64,
        // и первую share-ссылку в plain-списке.
        return out.toString(Charsets.UTF_8.name()).removePrefix("\uFEFF")
    }

    // ---------------------------------------------------------------- parsing

    private fun extractNodes(body: String): List<ProxyNode> {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return emptyList()

        if (trimmed.startsWith("[")) {
            val links = mutableListOf<String>()
            runCatching {
                val arr = JSONArray(trimmed)
                for (i in 0 until arr.length()) {
                    val item = arr.opt(i)
                    when (item) {
                        is String -> links.add(item)
                        is JSONObject -> {
                            item.optString("url").takeIf { it.isNotBlank() }?.let(links::add)
                            item.optString("link").takeIf { it.isNotBlank() }?.let(links::add)
                        }
                    }
                }
            }
            if (links.isNotEmpty()) return LinkParser.parseSubscription(links.joinToString("\n"))
        }

        if (trimmed.startsWith("{")) {
            val obj = runCatching { JSONObject(trimmed) }.getOrNull()
            if (obj != null) {
                val linksArray = obj.optJSONArray("links") ?: obj.optJSONArray("nodes")
                if (linksArray != null) {
                    val links = buildList {
                        for (i in 0 until linksArray.length()) {
                            val item = linksArray.opt(i)
                            if (item is String) add(item)
                        }
                    }
                    if (links.isNotEmpty()) {
                        return LinkParser.parseSubscription(links.joinToString("\n"))
                    }
                }
            }
        }

        // Панели часто отдают Clash YAML или SIP008 — тот же парсер, что и при
        // ручном импорте. Оставляем LinkParser как последний запасной вариант.
        val imported = runCatching { ConfigImporter.import(trimmed) }.getOrDefault(emptyList())
        if (imported.isNotEmpty()) return imported
        return LinkParser.parseSubscription(trimmed)
    }

    private fun parseInfo(headers: Map<String, String>): SubscriptionInfo {
        var upload = 0L
        var download = 0L
        var total = 0L
        var expire = 0L

        headers["subscription-userinfo"]?.split(";")?.forEach { part ->
            val key = part.substringBefore("=").trim().lowercase()
            val value = part.substringAfter("=", "").trim().toLongOrNull() ?: return@forEach
            when (key) {
                "upload" -> upload = value
                "download" -> download = value
                "total" -> total = value
                "expire" -> expire = value
            }
        }

        val rawTitle = headers["profile-title"].orEmpty()
        val title = when {
            rawTitle.startsWith("base64:", ignoreCase = true) ->
                LinkParser.decodeBase64(rawTitle.substringAfter(":"))?.trim().orEmpty()
            else -> rawTitle.trim()
        }

        val interval = headers["profile-update-interval"]?.toIntOrNull() ?: 12

        return SubscriptionInfo(
            title = title,
            upload = upload,
            download = download,
            total = total,
            expire = expire,
            updatedAt = System.currentTimeMillis(),
            webPageUrl = headers["profile-web-page-url"].orEmpty(),
            updateIntervalHours = interval.coerceIn(1, 168),
            hwidLimitReached = headers["x-hwid-max-devices-reached"] == "true" ||
                headers["x-hwid-limit"] == "true",
            hwidNotSupported = headers["x-hwid-not-supported"] == "true"
        )
    }

    companion object {
        private val ALLOWED_SCHEMES = setOf("http", "https")

        /** TRUE только для перехода с защищённого канала на открытый. */
        internal fun isDowngrade(from: String, to: String): Boolean =
            from.startsWith("https://", ignoreCase = true) &&
                to.startsWith("http://", ignoreCase = true)
        private const val CONNECT_TIMEOUT_MS = 12_000
        private const val READ_TIMEOUT_MS = 20_000
        private const val MAX_REDIRECTS = 5
        private const val MAX_BODY_BYTES = 2 * 1024 * 1024
        const val USER_AGENT = "RKN-VPN/1.0 (Android) mihomo/1.19.34"
    }
}
