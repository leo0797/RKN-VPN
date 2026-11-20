package app.rkn.vpn.core

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import app.rkn.vpn.BuildConfig
import app.rkn.vpn.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Скачивание APK во внутренний кэш и запуск системного установщика.
 * Браузер не открывается: файл отдаётся установщику через FileProvider.
 *
 * После ротации ключа не достаточно «похожего на APK» файла: проверяем и
 * контрольную сумму из релиза, и подпись, и адрес, от которого файл пришёл.
 */
object ApkInstaller {

    private const val CACHE_DIR = "updates"
    private const val MIME_APK = "application/vnd.android.package-archive"
    private const val BUFFER_SIZE = 64 * 1024
    private const val MIN_APK_SIZE = 1024 * 1024L
    private const val PREFIX = "rkn-vpn-"
    private const val MAX_REDIRECTS = 5

    /** Релизы и их ассеты живут только на GitHub. Любой другой хост — подмена. */
    private val ALLOWED_HOSTS = setOf(
        "github.com",
        "www.github.com",
        "objects.githubusercontent.com",
        "githubusercontent.com",
        "www.githubusercontent.com",
        "raw.githubusercontent.com"
    )

    /**
     * Удаляет мусор и уже установленный APK. Файл ещё не установленной версии
     * оставляем: если пользователь прервал установку, его можно использовать повторно.
     */
    fun cleanup(context: Context) {
        runCatching {
            updateDir(context).listFiles()?.forEach { file ->
                val name = file.name
                val version = name.removePrefix(PREFIX).removeSuffix(".apk")
                if (!name.startsWith(PREFIX) || !name.endsWith(".apk") || version == BuildConfig.VERSION_NAME) {
                    file.delete()
                }
            }
        }
    }

    /**
     * Возвращает APK из кэша, если он уже скачан и проходит все проверки.
     */
    private fun cached(context: Context, version: String, sha256: String?): File? {
        val file = File(updateDir(context), "$PREFIX$version.apk")
        if (!file.exists()) return null
        if (!isTrusted(context, file, sha256)) {
            file.delete()
            return null
        }
        return file
    }

    /**
     * Возвращает APK из кэша, если он уже скачан, иначе скачивает.
     * Прогресс приходит в 0..100.
     *
     * @param sha256 контрольная сумма из описания релиза; если её нет, checks
     *               ограничиваются подписью и hostname-ом.
     */
    suspend fun download(
        context: Context,
        url: String,
        version: String,
        onProgress: (Int) -> Unit,
        proxy: LocalProxy? = null,
        strings: Strings? = null,
        sha256: String? = null
    ): File = withContext(Dispatchers.IO) {
        fun message(id: Int, vararg args: Any): String =
            strings?.get(id, *args) ?: context.getString(id, *args)

        cached(context, version, sha256)?.let {
            withContext(Dispatchers.Main) { onProgress(100) }
            return@withContext it
        }
        cleanupAll(context)
        val dir = updateDir(context)
        val target = File(dir, "$PREFIX$version.apk")
        val temp = File(dir, "$PREFIX$version.apk.part")
        var current = runCatching { URL(url) }
            .getOrElse { error(message(R.string.error_download_invalid)) }
        try {
            var redirects = 0
            var digest = ""
            var written = 0L
            var total = 0
            while (true) {
                ensureAllowed(current) { message(R.string.error_download_host) }
                val connection = open(current, proxy)
                connection.requestMethod = "GET"
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.instanceFollowRedirects = false
                // Без принудительного identity некоторые CDN могут отдать gzip, и файл окажется «битым».
                connection.setRequestProperty("Accept-Encoding", "identity")
                connection.setRequestProperty("User-Agent", "RKN-VPN/${BuildConfig.VERSION_NAME}")
                try {
                    val code = connection.responseCode
                    if (code in 300..399) {
                        val location = connection.getHeaderField("Location")
                        if (location.isNullOrBlank()) error(message(R.string.error_download_invalid))
                        if (++redirects > MAX_REDIRECTS) error(message(R.string.error_download_redirects))
                        current = runCatching { URL(current, location) }
                            .getOrElse { error(message(R.string.error_download_invalid)) }
                        continue
                    }
                    if (code != HttpURLConnection.HTTP_OK) {
                        error(message(R.string.error_download_http, code))
                    }
                    total = connection.contentLength
                    val md = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(BUFFER_SIZE)
                    written = 0L
                    var lastPercent = -1
                    DigestOutputStream(temp.outputStream(), md).use { output ->
                        connection.inputStream.use { input ->
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                written += read
                                if (total > 0) {
                                    val percent = (written * 100 / total).toInt().coerceAtMost(100)
                                    if (percent != lastPercent) {
                                        lastPercent = percent
                                        withContext(Dispatchers.Main) { onProgress(percent) }
                                    }
                                }
                            }
                        }
                    }
                    digest = md.digest().joinToString("") { "%02x".format(it) }
                    break
                } finally {
                    connection.disconnect()
                }
            }

            if (written < MIN_APK_SIZE) error(message(R.string.error_download_too_small))
            if (total > 0 && written != total.toLong()) {
                error(message(R.string.error_download_incomplete))
            }
            if (!isValidApk(temp)) error(message(R.string.error_download_invalid))
            if (sha256 != null && !sha256.equals(digest, ignoreCase = true)) {
                error(message(R.string.error_download_hash))
            }
            if (!ApkSignature.matches(context, temp)) {
                error(message(R.string.error_download_signature))
            }
            if (!temp.renameTo(target)) {
                target.delete()
                if (!temp.renameTo(target)) error(message(R.string.error_download_save))
            }
            target
        } finally {
            temp.delete()
        }
    }

    /** Открывает системный установщик через content:// URI — без браузера. */
    fun install(context: Context, file: File) {
        if (!ApkSignature.matches(context, file)) {
            error(context.getString(R.string.error_download_signature))
        }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, MIME_APK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun open(url: URL, proxy: LocalProxy?): HttpURLConnection =
        if (proxy != null) proxy.open(url) else url.openConnection() as HttpURLConnection

    private fun ensureAllowed(url: URL, errorMessage: () -> String) {
        if (!url.protocol.equals("https", ignoreCase = true) || !isAllowedHost(url.host)) {
            error(errorMessage())
        }
    }

    private fun isAllowedHost(host: String): Boolean {
        val lower = host.lowercase().trimEnd('.')
        return lower in ALLOWED_HOSTS || lower.endsWith(".githubusercontent.com")
    }

    /** Все проверки кэшированного файла сразу. */
    private fun isTrusted(context: Context, file: File, sha256: String?): Boolean {
        if (file.length() < MIN_APK_SIZE || !isValidApk(file)) return false
        if (sha256 != null && !sha256.equals(sha256Of(file), ignoreCase = true)) return false
        return ApkSignature.matches(context, file)
    }

    private fun sha256Of(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                md.update(buffer, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun isValidApk(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val magic = ByteArray(4)
            input.read(magic) == magic.size &&
                magic[0] == 'P'.code.toByte() &&
                magic[1] == 'K'.code.toByte() &&
                magic[2] == 3.toByte() &&
                magic[3] == 4.toByte()
        } && ZipFile(file).use { it.getEntry("AndroidManifest.xml") != null }
    }.getOrDefault(false)

    /** Полная очистка перед загрузкой другой версии. */
    private fun cleanupAll(context: Context) {
        runCatching {
            updateDir(context).listFiles()?.forEach { it.delete() }
        }
    }

    private fun updateDir(context: Context): File =
        File(context.cacheDir, CACHE_DIR).apply { mkdirs() }
}
