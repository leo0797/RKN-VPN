package app.rkn.vpn.data

import android.os.Build
import app.rkn.vpn.core.LinkParser
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Загрузка подписки Remnawave.
 *
 * Поддерживаются оба распространённых варианта ответа:
 *  - base64-список share-ссылок (vless://, hysteria2://, ...);
 *  - plain-список ссылок.
 * Заголовки subscription-userinfo / profile-* дают трафик, срок и название.
 */
class SubscriptionRepository(private val prefs: Prefs) {

    data class Result(
        val nodes: List<ProxyNode>,
        val info: SubscriptionInfo
    )

    fun fetch(url: String): Result {
        if (url.isBlank()) error("Ссылка на подписку не задана")
        val response = request(url.trim())
        if (response.code !in 200..299) {
            val hint = when (response.code) {
                404 -> "Подписка не найдена (404). Проверьте ссылку. Если в Remnawave включён HWID-лимит, устройство могло быть не зарегистрировано."
                401, 403 -> "Доступ запрещён (${response.code}). Проверьте ссылку на подписку."
                else -> "Сервер вернул HTTP ${response.code}."
            }
            error(hint)
        }

        val nodes = extractNodes(response.body)
        if (nodes.isEmpty()) {
            error("В ответе нет поддерживаемых узлов. Проверьте формат подписки (vless/hysteria2/tuic/ss/trojan/vmess).")
        }

        val info = parseInfo(response.headers)
        return Result(nodes, info)
    }

    // ------------------------------------------------------------- transport

    private data class Response(
        val code: Int,
        val body: String,
        val headers: Map<String, String>
    )

    private fun request(url: String): Response {
        var current = url
        var lastError: Exception? = null
        for (attempt in 0..5) {
            try {
                val parsed = URL(current)
                // Только http/https: file:, jar: и прочие схемы не должны
                // открываться по ссылке из настроек.
                if (parsed.protocol?.lowercase() !in ALLOWED_SCHEMES) {
                    error("Поддерживаются только ссылки http:// и https://")
                }
                val conn = (parsed.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    instanceFollowRedirects = false
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", USER_AGENT)
                    setRequestProperty("Accept", "*/*")
                    setRequestProperty("Accept-Encoding", "identity")
                    setRequestProperty("x-hwid", prefs.hwid)
                    setRequestProperty("x-device-os", "Android")
                    setRequestProperty("x-ver-os", Build.VERSION.RELEASE ?: "unknown")
                    setRequestProperty(
                        "x-device-model",
                        listOf(Build.MANUFACTURER, Build.MODEL)
                            .filter { it.isNotBlank() }
                            .joinToString(" ")
                            .trim()
                    )
                }

                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (location.isNullOrBlank()) error("Редирект без Location")
                    current = URL(URL(current), location).toString()
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
                conn.disconnect()
                return Response(code, body, headers)
            } catch (e: Exception) {
                lastError = e
            }
        }
        error("Не удалось загрузить подписку: ${lastError?.message ?: "неизвестная ошибка"}")
    }

    private fun readAll(stream: java.io.InputStream): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = stream.read(buffer)
            if (read <= 0) break
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

        return LinkParser.parseSubscription(body)
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
        const val USER_AGENT = "RKN-VPN/1.0 (Android) mihomo/1.19.34"
    }
}
