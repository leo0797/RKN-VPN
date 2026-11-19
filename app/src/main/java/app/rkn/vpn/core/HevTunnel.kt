package app.rkn.vpn.core

import hev.htproxy.TProxyService

/** Обёртка над tun2socks-библиотекой hev-socks5-tunnel (AAR). */
object HevTunnel {

    fun start(configPath: String, fd: Int): Boolean =
        runCatching { TProxyService.TProxyStartService(configPath, fd) }.getOrDefault(false)

    fun stop() {
        runCatching { TProxyService.TProxyStopService() }
    }

    fun isRunning(): Boolean =
        runCatching { TProxyService.TProxyIsRunning() }.getOrDefault(false)

    /** [txPackets, txBytes, rxPackets, rxBytes] */
    fun stats(): LongArray {
        val value = runCatching { TProxyService.TProxyGetStats() }.getOrNull()
        return value ?: LongArray(0)
    }

    fun buildConfig(socksPort: Int, username: String, password: String): String = buildString {
        appendLine("tunnel:")
        appendLine("  mtu: 8500")
        appendLine("  ipv4: 198.18.0.1")
        appendLine("  ipv6: 'fc00::1'")
        appendLine("  icmp: 'off'")
        appendLine("socks5:")
        appendLine("  port: $socksPort")
        appendLine("  address: '127.0.0.1'")
        appendLine("  udp: 'udp'")
        appendLine("  username: '$username'")
        appendLine("  password: '$password'")
        appendLine("mapdns:")
        appendLine("  address: 198.18.0.2")
        appendLine("  port: 53")
        appendLine("  network: 240.0.0.0")
        appendLine("  netmask: 240.0.0.0")
        appendLine("  cache-size: 10000")
        appendLine("misc:")
        appendLine("  task-stack-size: 81920")
        appendLine("  log-level: warn")
    }
}
