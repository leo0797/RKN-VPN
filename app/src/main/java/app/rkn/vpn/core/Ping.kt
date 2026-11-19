package app.rkn.vpn.core

import java.net.InetSocketAddress
import java.net.Socket

object Ping {
    /** TCP-хендшейк до узла. Для UDP-протоколов (hysteria2/tuic) может быть null. */
    fun tcp(host: String, port: Int, timeoutMs: Int = 2500): Int? {
        val clean = host.removePrefix("[").removeSuffix("]")
        if (clean.isBlank() || port <= 0) return null
        return runCatching {
            val started = System.nanoTime()
            Socket().use { socket ->
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(clean, port), timeoutMs)
            }
            ((System.nanoTime() - started) / 1_000_000L).toInt()
        }.getOrNull()
    }
}
