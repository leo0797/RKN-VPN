package app.rkn.vpn.core

import android.content.Context
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Запуск mihomo как отдельного процесса.
 *
 * Бинарник лежит в nativeLibraryDir под именем libmihomo.so — это единственное
 * место, откуда Android разрешает исполнять файлы без root.
 */
class CoreRunner(context: Context) {

    private val appContext = context.applicationContext
    private val workDir = File(appContext.filesDir, "core")
    private val configFile = File(workDir, "config.json")
    private val logFile = File(workDir, "mihomo.log")
    private var process: Process? = null

    val binary: File
        get() = File(appContext.applicationInfo.nativeLibraryDir, BINARY_NAME)

    fun isAvailable(): Boolean = runCatching {
        val f = binary
        f.exists() && f.length() > 1_000_000
    }.getOrDefault(false)

    fun isRunning(): Boolean = process?.isAlive == true

    fun start(configJson: String, readyPort: Int) {
        stop()
        if (!isAvailable()) {
            error("Ядро mihomo не найдено в APK. Пересоберите приложение (см. README).")
        }
        workDir.mkdirs()
        configFile.writeText(configJson, Charsets.UTF_8)
        runCatching { logFile.delete() }

        val pb = ProcessBuilder(
            binary.absolutePath,
            "-d", workDir.absolutePath,
            "-f", configFile.absolutePath
        )
        pb.redirectErrorStream(true)
        pb.redirectOutput(logFile)
        pb.environment()["TMPDIR"] = appContext.cacheDir.absolutePath
        process = pb.start()

        // Ждём, пока mihomo откроет mixed-port.
        val deadline = System.currentTimeMillis() + READY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val proc = process ?: break
            if (!proc.isAlive) {
                error("mihomo завершился при запуске.\n${logTail(25)}")
            }
            if (canConnect(readyPort)) return
            Thread.sleep(200)
        }
        stop()
        error("mihomo не успел запуститься за ${READY_TIMEOUT_MS / 1000} с.\n${logTail(25)}")
    }

    fun stop() {
        val proc = process ?: return
        process = null
        runCatching { proc.destroy() }
        runCatching { proc.waitFor(2, java.util.concurrent.TimeUnit.SECONDS) }
        if (proc.isAlive) runCatching { proc.destroyForcibly() }
    }

    fun logTail(maxLines: Int = 120): String {
        return runCatching {
            if (!logFile.exists()) {
                ""
            } else {
                logFile.readLines(Charsets.UTF_8).takeLast(maxLines).joinToString("\n")
            }
        }.getOrDefault("")
    }

    fun clearLog() {
        runCatching { logFile.writeText("") }
    }

    private fun canConnect(port: Int): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 300)
        }
        true
    }.getOrDefault(false)

    companion object {
        private const val BINARY_NAME = "libmihomo.so"
        private const val READY_TIMEOUT_MS = 20_000L
    }
}
