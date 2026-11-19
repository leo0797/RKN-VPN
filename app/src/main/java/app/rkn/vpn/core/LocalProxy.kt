package app.rkn.vpn.core

import android.util.Base64
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/**
 * Локальный mixed-port mihomo с учётными данными текущей сессии.
 *
 * Приложение исключено из собственного TUN (иначе петля), поэтому запросы
 * панели подписки и GitHub идут напрямую. Если domain заблокирован сетью,
 * повторяем тот же запрос через локальный прокси ядра — тогда трафик идёт
 * через выбранный outbound и не зависит от блокировок провайдера.
 */
data class LocalProxy(
    val port: Int,
    val user: String,
    val password: String
) {
    private val javaProxy: Proxy =
        Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port))

    /** Открывает соединение через mixed-port и сразу кладёт учётные данные. */
    fun open(url: URL): HttpURLConnection {
        val connection = url.openConnection(javaProxy) as HttpURLConnection
        if (user.isNotBlank()) {
            val token = Base64.encodeToString(
                "$user:$password".toByteArray(Charsets.UTF_8),
                Base64.NO_WRAP
            )
            connection.setRequestProperty("Proxy-Authorization", "Basic $token")
        }
        return connection
    }
}
