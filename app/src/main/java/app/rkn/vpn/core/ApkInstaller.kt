package app.rkn.vpn.core

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import app.rkn.vpn.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipFile

/**
 * Скачивание APK во внутренний кэш и запуск системного установщика.
 * Браузер не открывается: файл отдаётся установщику через FileProvider.
 */
object ApkInstaller {

    private const val CACHE_DIR = "updates"
    private const val MIME_APK = "application/vnd.android.package-archive"
    private const val BUFFER_SIZE = 64 * 1024
    private const val MIN_APK_SIZE = 1024 * 1024L
    private const val PREFIX = "rkn-vpn-"

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

    /** Уже скачанный и валидный APK нужной версии, либо null. */
    private fun cached(context: Context, version: String): File? {
        val file = File(updateDir(context), "$PREFIX$version.apk")
        if (!file.exists()) return null
        if (file.length() < MIN_APK_SIZE || !isValidApk(file)) {
            file.delete()
            return null
        }
        return file
    }

    /**
     * Возвращает APK из кэша, если он уже скачан, иначе скачивает.
     * Прогресс приходит в 0..100.
     */
    suspend fun download(
        context: Context,
        url: String,
        version: String,
        onProgress: (Int) -> Unit
    ): File = withContext(Dispatchers.IO) {
        cached(context, version)?.let {
            withContext(Dispatchers.Main) { onProgress(100) }
            return@withContext it
        }
        cleanupAll(context)
        val dir = updateDir(context)
        val target = File(dir, "$PREFIX$version.apk")
        val temp = File(dir, "$PREFIX$version.apk.part")
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        // Без принудительного identity некоторые CDN могут отдать gzip, и файл окажется «битым».
        connection.setRequestProperty("Accept-Encoding", "identity")
        connection.setRequestProperty("User-Agent", "RKN-VPN/${BuildConfig.VERSION_NAME}")
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                error("HTTP ${connection.responseCode}")
            }
            val total = connection.contentLength
            val buffer = ByteArray(BUFFER_SIZE)
            var written = 0L
            var lastPercent = -1
            connection.inputStream.use { input ->
                temp.outputStream().use { output ->
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
            if (written < MIN_APK_SIZE) error("APK too small")
            if (total > 0 && written != total.toLong()) error("incomplete download")
            if (!isValidApk(temp)) error("invalid APK file")
            if (!temp.renameTo(target)) {
                target.delete()
                if (!temp.renameTo(target)) error("cannot save APK")
            }
            target
        } finally {
            connection.disconnect()
            temp.delete()
        }
    }

    /** Открывает системный установщик через content:// URI — без браузера. */
    fun install(context: Context, file: File) {
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
