package app.rkn.vpn.core

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Локальный API mihomo. Используется для настоящей проверки узла: mihomo сам
 * открывает соединение через outbound, в том числе по UDP (Hysteria2, TUIC),
 * чего TCP-пинг из приложения сделать не может.
 */
object MihomoApi {

    const val DEFAULT_TEST_URL = "https://www.gstatic.com/generate_204"

    /** Возвращает задержку в мс или null, если узел недоступен. */
    fun delay(
        apiPort: Int,
        secret: String,
        proxyName: String,
        timeoutMs: Int = 5_000,
        testUrl: String = DEFAULT_TEST_URL
    ): Int? = runCatching {
        val encodedName = URLEncoder.encode(proxyName, "UTF-8")
        val encodedUrl = URLEncoder.encode(testUrl, "UTF-8")
        val url = URL("http://127.0.0.1:$apiPort/proxies/$encodedName/delay" +
            "?url=$encodedUrl&timeout=$timeoutMs")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = timeoutMs + 1_000
        connection.readTimeout = timeoutMs + 1_000
        if (secret.isNotBlank()) {
            connection.setRequestProperty("Authorization", "Bearer $secret")
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val value = JSONObject(body).optInt("delay", -1)
            value.takeIf { it > 0 }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()
}
