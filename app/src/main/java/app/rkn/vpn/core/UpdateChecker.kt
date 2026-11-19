package app.rkn.vpn.core

import android.os.Build
import app.rkn.vpn.BuildConfig
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Проверка новых релизов через публичный GitHub Releases API.
 * Ссылка на репозиторий задаётся в build.gradle.kts (UPDATE_REPO).
 */
object UpdateChecker {

    data class Release(
        val version: String,
        val notes: String,
        val apkUrl: String?,
        val pageUrl: String
    )

    suspend fun check(): Release? = withContext(Dispatchers.IO) {
        checkRepo(BuildConfig.UPDATE_REPO)
    }

    internal fun checkRepo(repo: String): Release? = runCatching {
        val connection = URL("https://api.github.com/repos/$repo/releases/latest")
            .openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 8_000
        connection.readTimeout = 8_000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "RKN-VPN/${BuildConfig.VERSION_NAME}")
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            parse(body)
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    internal fun parse(body: String): Release? {
        val json = JSONObject(body)
        val version = json.optString("tag_name").removePrefix("v").trim()
        if (version.isEmpty() || !isNewer(version, BuildConfig.VERSION_NAME)) return null
        val assets = json.optJSONArray("assets")
        val preferredAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: ""
        var fallback: String? = null
        var apkUrl: String? = null
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name")
                if (!name.endsWith(".apk", ignoreCase = true)) continue
                val url = asset.optString("browser_download_url")
                if (url.isEmpty()) continue
                if (fallback == null) fallback = url
                if (preferredAbi.isNotEmpty() && name.contains(preferredAbi, ignoreCase = true)) {
                    apkUrl = url
                    break
                }
            }
        }
        return Release(
            version = version,
            notes = json.optString("body").trim(),
            apkUrl = apkUrl ?: fallback,
            pageUrl = json.optString("html_url").ifBlank {
                "https://github.com/${BuildConfig.UPDATE_REPO}/releases"
            }
        )
    }

    /** Сравнивает версии вида 1.0.4, игнорируя суффиксы вроде -beta. */
    internal fun isNewer(candidate: String, current: String): Boolean {
        fun parts(value: String) = value.substringBefore('-')
            .split('.')
            .map { it.toIntOrNull() ?: 0 }
        val a = parts(candidate)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val left = a.getOrElse(i) { 0 }
            val right = b.getOrElse(i) { 0 }
            if (left != right) return left > right
        }
        return false
    }
}
