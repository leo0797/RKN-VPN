package app.rkn.vpn.core

/**
 * Разрешение строковых ресурсов из слоёв без доступа к Activity/Context.
 * Позволяет не показывать русскоязычные error("...") в английском интерфейсе.
 */
fun interface Strings {
    fun get(id: Int, vararg args: Any): String
}
