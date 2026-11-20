package app.rkn.vpn.util

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Локальный журнал необработанных исключений.
 *
 * Пишется только во внутреннее хранилище приложения и попадает в диагностику
 * только по кнопке пользователя. Ссылки вырезаются: в сообщении об ошибке
 * может оказаться URL подписки вместе с токеном.
 */
object CrashLog {

    private const val FILE_NAME = "crash.log"
    private const val MAX_CHARS = 20_000

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is Handler) return
        Thread.setDefaultUncaughtExceptionHandler(Handler(app, previous))
    }

    fun read(context: Context): String = runCatching {
        File(context.applicationContext.filesDir, FILE_NAME).readText()
    }.getOrDefault("")

    fun clear(context: Context) {
        runCatching { File(context.applicationContext.filesDir, FILE_NAME).delete() }
    }

    private class Handler(
        private val context: Context,
        private val previous: Thread.UncaughtExceptionHandler?
    ) : Thread.UncaughtExceptionHandler {

        override fun uncaughtException(thread: Thread, error: Throwable) {
            runCatching {
                val entry = buildString {
                    appendLine("=== ${System.currentTimeMillis()} / ${thread.name} ===")
                    appendLine(stackTrace(error))
                }
                val file = File(context.filesDir, FILE_NAME)
                val previousText = runCatching { file.readText() }.getOrDefault("")
                file.writeText((previousText + entry).takeLast(MAX_CHARS))
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun stackTrace(error: Throwable): String {
        val writer = StringWriter()
        error.printStackTrace(PrintWriter(writer))
        return writer.toString().replace(Regex("https?://[^\\s)\"']+"), "<url>")
    }
}
