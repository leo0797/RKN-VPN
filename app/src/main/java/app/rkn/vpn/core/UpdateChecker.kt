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
        /** SHA-256 ассета из описания релиза — проверяется перед установкой. */
        val apkSha256: String?,
        val pageUrl: String
    )

    /**
     * Раньше null означал и «нет обновления», и «нет сети». Теперь состояния
     * разделены: UI может честно сказать, почему проверка не удалась.
     */
    sealed class Result {
        data class Available(val release: Release) : Result()
        data object UpToDate : Result()
        data class Failed(val reason: Failure) : Result()

        enum class Failure { NETWORK, SERVER, MALFORMED }
    }

    suspend fun check(proxy: LocalProxy? = null): Result = withContext(Dispatchers.IO) {
        checkRepo(BuildConfig.UPDATE_REPO, proxy)
    }

    internal fun checkRepo(repo: String, proxy: LocalProxy? = null): Result =
        checkUrl("https://api.github.com/repos/$repo/releases/latest", proxy)

    internal fun checkUrl(urlString: String, proxy: LocalProxy? = null): Result = runCatching {
        val url = URL(urlString)
        val connection = if (proxy != null) {
            proxy.open(url)
        } else {
            url.openConnection() as HttpURLConnection
        }
        connection.requestMethod = "GET"
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "RKN-VPN/${BuildConfig.VERSION_NAME}")
        try {
            when (connection.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    val body = connection.inputStream.bufferedReader(Charsets.UTF_8)
                        .use { it.readText() }
                    val json = runCatching { JSONObject(body) }.getOrElse {
                        return Result.Failed(Result.Failure.MALFORMED)
                    }
                    if (!json.has("tag_name")) {
                        return Result.Failed(Result.Failure.MALFORMED)
                    }
                    val release = parse(body)
                    if (release != null) Result.Available(release) else Result.UpToDate
                }
                else -> Result.Failed(Result.Failure.SERVER)
            }
        } finally {
            connection.disconnect()
        }
    }.getOrElse { Result.Failed(Result.Failure.NETWORK) }

    internal fun parse(body: String): Release? {
        val json = JSONObject(body)
        val version = json.optString("tag_name").removePrefix("v").trim()
        if (version.isEmpty() || !isNewer(version, BuildConfig.VERSION_NAME)) return null
        val assets = json.optJSONArray("assets")
        val preferredAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: ""
        var fallback: String? = null
        var apkUrl: String? = null
        var apkName: String? = null
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
                    apkName = name
                    break
                }
            }
        }
        val notes = json.optString("body").trim()
        val chosenUrl = apkUrl ?: fallback
        val hashes = parseChecksums(notes)
        return Release(
            version = version,
            notes = notes,
            apkUrl = chosenUrl,
            apkSha256 = apkName?.let { hashes[it] }
                ?: hashes.values.singleOrNull(),
            pageUrl = json.optString("html_url").ifBlank {
                "https://github.com/${BuildConfig.UPDATE_REPO}/releases"
            }
        )
    }

    /**
     * CI дописывает в описание релиза строки вида
     * `app-arm64-v8a-release.apk: <64 hex>`. Принимаем и обратный порядок,
     * и обёртку в backticks — чтобы старые релизы не ломали парсер.
     */
    internal fun parseChecksums(notes: String): Map<String, String> {
        val hashRegex = Regex("[a-fA-F0-9]{64}")
        val nameRegex = Regex("[A-Za-z0-9._-]+\\.apk", RegexOption.IGNORE_CASE)
        return buildMap {
            notes.lineSequence().forEach { line ->
                val hash = hashRegex.find(line)?.value?.lowercase() ?: return@forEach
                val names = nameRegex.findAll(line).map { it.value }.toList()
                if (names.size == 1) put(names.first(), hash)
            }
        }
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
