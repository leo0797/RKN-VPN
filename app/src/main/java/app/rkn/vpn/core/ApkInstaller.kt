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

/**
 * Скачивание APK во внутренний кэш и запуск системного установщика.
 * Браузер не открывается: файл отдаётся установщику через FileProvider.
 */
object ApkInstaller {

    private const val CACHE_DIR = "updates"
    private const val MIME_APK = "application/vnd.android.package-archive"
    private const val BUFFER_SIZE = 64 * 1024

    /** Удаляет готовые APK. Вызывается при возврате в приложение, чтобы не трогать текущую загрузку. */
    fun cleanup(context: Context) {
        runCatching {
            updateDir(context).listFiles()?.forEach { file ->
                if (file.name.endsWith(".apk", ignoreCase = true)) file.delete()
            }
        }
    }

    /** Полная очистка, в том числе незавершённых файлов. Только перед новой загрузкой. */
    private fun cleanupAll(context: Context) {
        runCatching {
            updateDir(context).listFiles()?.forEach { it.delete() }
        }
    }

    /**
     * Скачивает APK в кэш. Прогресс приходит в 0..100.
     * При любой ошибке временный файл удаляется, исключение пробрасывается вызывающему.
     */
    suspend fun download(
        context: Context,
        url: String,
        version: String,
        onProgress: (Int) -> Unit
    ): File = withContext(Dispatchers.IO) {
        cleanupAll(context)
        val dir = updateDir(context)
        val target = File(dir, "rkn-vpn-$version.apk")
        val temp = File(dir, "rkn-vpn-$version.apk.part")
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
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
            if (written == 0L) error("empty APK")
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

    private fun updateDir(context: Context): File =
        File(context.cacheDir, CACHE_DIR).apply { mkdirs() }
}
