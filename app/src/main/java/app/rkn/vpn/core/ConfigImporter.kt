package app.rkn.vpn.core

import android.util.Base64
import app.rkn.vpn.data.ProxyNode
import org.json.JSONArray
import org.json.JSONObject

/**
 * Универсальный импорт описаний прокси, которые НЕ являются share-ссылками:
 * конфигов Clash/Mihomo (`proxies:`) и SIP008 (JSON с массивом `servers`).
 *
 * Собственный YAML-парсер — намеренно маленький: он понимает только то,
 * что реально встречается в секции `proxies`. Никаких внешних зависимостей,
 * только org.json и стандартная библиотека. Каждая запись разбирается через
 * runCatching: одна битая строка не должна ронять импорт целиком.
 */
object ConfigImporter {

    private const val BOM = '\uFEFF'

    /** Типы Clash, которые mihomo понимает как outbound. */
    private val SUPPORTED_TYPES = setOf(
        "vless", "vmess", "trojan", "ss", "hysteria2", "hy2",
        "tuic", "anytls", "wireguard"
    )

    // ------------------------------------------------------------- детекты

    /** true, если после BOM/пробелов в тексте есть ключ верхнего уровня `proxies:`. */
    fun looksLikeClashYaml(text: String): Boolean {
        val normalized = text.removePrefix(BOM.toString())
        return normalized.lineSequence().any { line ->
            line.startsWith("proxies:")
        }
    }

    /** true, если текст — SIP008: объект с массивом `servers`, похожих на SIP008. */
    fun looksLikeSip008(text: String): Boolean = runCatching {
        val o = JSONObject(text.removePrefix(BOM.toString()).trim())
        val servers = o.optJSONArray("servers") ?: return@runCatching false
        (0 until servers.length()).any { i ->
            val s = servers.optJSONObject(i) ?: return@any false
            val port = s.opt("server_port")
            s.has("server") && port != null && port.toString().toIntOrNull() != null &&
                s.has("password") && s.has("method")
        }
    }.getOrDefault(false)

    /** Автоопределение формата. Всё, что не Clash/SIP008, отдаём [LinkParser]. */
    fun import(text: String): List<ProxyNode> {
        val clean = text.removePrefix(BOM.toString())
        return when {
            looksLikeClashYaml(clean) -> parseClashYaml(clean)
            looksLikeSip008(clean) -> parseSip008(clean)
            clean.contains("://") -> LinkParser.parseSubscription(clean)
            else -> {
                val decoded = decodeBase64(clean)
                if (decoded != null) {
                    when {
                        looksLikeClashYaml(decoded) -> parseClashYaml(decoded)
                        looksLikeSip008(decoded) -> parseSip008(decoded)
                        else -> LinkParser.parseSubscription(decoded)
                    }
                } else {
                    LinkParser.parseSubscription(clean)
                }
            }
        }
    }

    // ------------------------------------------------------------ Clash YAML

    /** Разбирает секцию `proxies:` конфига Clash/Mihomo в готовые outbound'ы. */
    fun parseClashYaml(text: String): List<ProxyNode> {
        val normalized = text.removePrefix(BOM.toString()).replace("\r\n", "\n").replace('\r', '\n')
        val lines = normalized.lineSequence().mapNotNull { raw ->
            val content = stripComment(raw)
            if (content.isBlank()) null else YLine(indentOf(content), content.trimStart())
        }.toList()

        val proxiesIdx = lines.indexOfFirst { line ->
            line.indent == 0 && line.text.startsWith("proxies:")
        }
        if (proxiesIdx < 0) return emptyList()

        val inline = lines[proxiesIdx].text.substringAfter(":", "").trim()
        if (inline.isNotEmpty()) {
            // Встречается компактная форма `proxies: [{name: A, type: ss, ...}]`.
            return runCatching {
                val arr = parseScalar(inline) as? JSONArray ?: return@runCatching emptyList()
                (0 until arr.length()).mapNotNull { i ->
                    (mapToJson(arr.opt(i)) as? JSONObject)?.let { nodeOf(it) }
                }
            }.getOrDefault(emptyList())
        }

        // Секция proxies: уже найдена, поэтому сразу считаем, что внутри неё.
        var section = true
        var proxyIndent: Int? = null
        var stack = mutableListOf<YCtx>()
        val proxies = mutableListOf<MutableMap<String, Any?>>()

        for (line in lines.drop(proxiesIdx + 1)) {
            runCatching {
                // Ключ верхнего уровня завершает секцию proxies.
                if (line.indent == 0) {
                    section = line.text.startsWith("proxies:")
                    stack = mutableListOf()
                    return@runCatching
                }
                if (!section) return@runCatching

                if (line.text.startsWith('-')) {
                    val dashes = line.text.takeWhile { it == '-' }.length
                    val rest = line.text.substring(dashes).trimStart()
                    val itemLevel = line.indent + 2 * dashes
                    val baseIndent = proxyIndent ?: line.indent.also { proxyIndent = it }
                    if (line.indent > baseIndent) {
                        // Вложенный список внутри прокси (например, alpn в block-стиле)
                        // не является новым outbound'ом.
                        return@runCatching
                    }
                    while ((stack.lastOrNull()?.level ?: -1) >= itemLevel) {
                        stack.removeAt(stack.lastIndex)
                    }
                    val map = mutableMapOf<String, Any?>()
                    val (key, value) = if (rest.isEmpty()) "" to "" else splitKeyValue(rest)
                    if (key.isNotEmpty()) {
                        map[key] = if (value.isEmpty()) mutableMapOf<String, Any?>() else parseScalar(value)
                    }
                    proxies.add(map)
                    stack.add(YCtx(itemLevel, map))
                    // `- key:` — вложенный map начинается на следующей строке.
                    if (value.isEmpty() && key.isNotEmpty()) {
                        @Suppress("UNCHECKED_CAST")
                        stack.add(YCtx(itemLevel + 2, map[key] as MutableMap<String, Any?>))
                    }
                    return@runCatching
                }

                if (stack.isEmpty()) return@runCatching
                val level = line.indent
                val (key, value) = splitKeyValue(line.text)
                if (key.isEmpty()) return@runCatching
                while (stack.last().level > level) stack.removeAt(stack.lastIndex)
                val parent = (stack.lastOrNull() as? YCtx)?.map ?: return@runCatching
                if (value.isEmpty()) {
                    val child = mutableMapOf<String, Any?>()
                    parent[key] = child
                    stack.add(YCtx(level + 2, child))
                } else {
                    parent[key] = parseScalar(value)
                }
            }
        }

        // Вложенные map/list уже преобразованы в JSONObject/JSONArray при nodeOf.
        return proxies.mapNotNull { nodeOf(mapToJson(it) as? JSONObject ?: return@mapNotNull null) }
    }

    private fun nodeOf(json: JSONObject): ProxyNode? {
        val type = json.optString("type").trim().lowercase()
        if (type !in SUPPORTED_TYPES) return null
        val server = scalarText(json.opt("server")).trim()
        if (server.isEmpty()) return null
        val port = scalarText(json.opt("port")).trim().toIntOrNull()
            ?.takeIf { it in 1..65535 } ?: return null

        val rawName = scalarText(json.opt("name")).trim()
        val name = LinkParser.sanitizeName(rawName.ifBlank { "Clash node" })
        val id = "clash-" + positiveHex("$rawName$server$port".hashCode())
        return ProxyNode(
            id = id,
            name = name,
            protocol = type,
            server = server,
            port = port,
            raw = json.toString()
        )
    }

    // ---------------------------------------------------------------- SIP008

    /** Разбирает SIP008-конфиг (`{"servers":[...]}`) в outbound'ы Shadowsocks. */
    fun parseSip008(text: String): List<ProxyNode> = runCatching {
        val o = JSONObject(text.removePrefix(BOM.toString()).trim())
        val servers = o.optJSONArray("servers") ?: return@runCatching emptyList()
        buildList {
            for (i in 0 until servers.length()) {
                runCatching {
                    val s = servers.optJSONObject(i) ?: return@runCatching null
                    val server = s.optString("server").trim()
                    val port = s.opt("server_port")?.toString()?.toIntOrNull()
                        ?.takeIf { it in 1..65535 } ?: return@runCatching null
                    val method = s.optString("method").trim()
                    val password = s.optString("password")
                    if (server.isEmpty() || method.isEmpty() || password.isEmpty()) return@runCatching null

                    val rawName = listOf("remarks", "title", "id")
                        .map { s.optString(it).trim() }
                        .firstOrNull { it.isNotEmpty() } ?: "SIP008 node"
                    val proxy = JSONObject()
                        .put("name", LinkParser.sanitizeName(rawName))
                        .put("type", "ss")
                        .put("server", server)
                        .put("port", port)
                        .put("cipher", method.lowercase())
                        .put("password", password)
                        .put("udp", true)

                    val plugin = s.optString("plugin").trim()
                    if (plugin.isNotEmpty()) {
                        val mapped = when (plugin.lowercase()) {
                            "obfs-local", "simple-obfs", "obfs" -> "obfs"
                            "v2ray-plugin" -> "v2ray-plugin"
                            else -> null
                        }
                        if (mapped != null) {
                            proxy.put("plugin", mapped)
                            val opts = parsePluginOpts(s.optString("plugin_opts"))
                            if (opts.length() > 0) proxy.put("plugin-opts", opts)
                        }
                    }

                    val id = "sip-" + positiveHex("$rawName$server$port".hashCode())
                    this@buildList.add(
                        ProxyNode(
                            id = id,
                            name = LinkParser.sanitizeName(rawName),
                            protocol = "ss",
                            server = server,
                            port = port,
                            raw = proxy.toString()
                        )
                    )
                    null
                }
            }
        }
    }.getOrDefault(emptyList())

    /** `mode=http;host=example.com;tls=true` -> объект с правильными типами. */
    private fun parsePluginOpts(value: String): JSONObject {
        val result = JSONObject()
        value.split(';').map { it.trim() }.filter { it.isNotEmpty() }.forEach { part ->
            val rawKey = part.substringBefore('=').trim()
            // В SIP008 режим obfs-local называется obfs=..., а mihomo ждёт mode=...
            val key = if (rawKey == "obfs") "mode" else rawKey
            val raw = part.substringAfter('=', "").trim()
            if (key.isEmpty()) return@forEach
            val converted = when {
                raw.equals("true", true) -> true
                raw.equals("false", true) -> false
                raw.toIntOrNull() != null && key != "path" && key != "host" -> raw.toInt()
                else -> raw
            }
            result.put(key, converted)
        }
        return result
    }

    // ------------------------------------------------------ маленький YAML

    private data class YLine(val indent: Int, val text: String)

    private class YCtx(val level: Int, val map: MutableMap<String, Any?>)

    private fun indentOf(line: String): Int = line.takeWhile { it == ' ' || it == '\t' }.length

    /** Убирает комментарий вне кавычек ('#' внутри значения — часть строки). */
    private fun stripComment(line: String): String {
        var quote: Char? = null
        var escaped = false
        for (i in line.indices) {
            val c = line[i]
            if (escaped) {
                escaped = false
                continue
            }
            when {
                c == '\\' && quote == '"' -> escaped = true
                c == '\'' && quote == null -> quote = '\''
                c == '\'' && quote == '\'' -> {
                    if (i + 1 < line.length && line[i + 1] == '\'') continue else quote = null
                }
                c == '"' && quote == null -> quote = '"'
                c == '"' && quote == '"' -> quote = null
                c == '#' && quote == null && (i == 0 || line[i - 1].isWhitespace()) ->
                    return line.substring(0, i)
            }
        }
        return line
    }

    /** Разделяет `key: value` по первому двоеточию вне кавычек. */
    private fun splitKeyValue(text: String): Pair<String, String> {
        var quote: Char? = null
        var escaped = false
        for (i in text.indices) {
            val c = text[i]
            if (escaped) {
                escaped = false
                continue
            }
            when {
                c == '\\' && quote == '"' -> escaped = true
                c == '\'' && quote == null -> quote = '\''
                c == '\'' && quote == '\'' -> quote = null
                c == '"' && quote == null -> quote = '"'
                c == '"' && quote == '"' -> quote = null
                c == ':' && quote == null -> return unquote(text.substring(0, i).trim()) to
                    text.substring(i + 1).trim()
            }
        }
        return unquote(text.trim()) to ""
    }

    /** Скаляр YAML -> JUnit-совместимое значение (String/Boolean/Int/JSONArray/JSONObject). */
    private fun parseScalar(raw: String): Any? {
        val value = raw.trim()
        if (value.isEmpty()) return ""
        return when {
            value.startsWith("[") && value.endsWith("]") ->
                parseFlowArray(value.substring(1, value.length - 1))
            value.startsWith("{") && value.endsWith("}") ->
                parseFlowObject(value.substring(1, value.length - 1))
            else -> parseAtom(value)
        }
    }

    private fun parseFlowArray(body: String): JSONArray {
        val arr = JSONArray()
        splitFlow(body).filter { it.isNotEmpty() }.forEach { arr.put(mapToJson(parseScalar(it))) }
        return arr
    }

    private fun parseFlowObject(body: String): MutableMap<String, Any?> {
        val map = linkedMapOf<String, Any?>()
        splitFlow(body).filter { it.isNotEmpty() }.forEach { pair ->
            val (key, value) = splitKeyValue(pair)
            if (key.isNotEmpty()) map[key] = if (value.isEmpty()) "" else parseScalar(value)
        }
        return map
    }

    /** Разделяет потоковую последовательность по запятым вне кавычек/скобок. */
    private fun splitFlow(body: String): List<String> {
        val parts = mutableListOf<String>()
        var start = 0
        var depth = 0
        var quote: Char? = null
        var escaped = false
        for (i in body.indices) {
            val c = body[i]
            if (escaped) {
                escaped = false
                continue
            }
            when {
                c == '\\' && quote == '"' -> escaped = true
                c == '\'' && quote == null -> quote = '\''
                c == '\'' && quote == '\'' -> quote = null
                c == '"' && quote == null -> quote = '"'
                c == '"' && quote == '"' -> quote = null
                c in "[{" && quote == null -> depth++
                c in "]}" && quote == null -> depth--
                c == ',' && quote == null && depth == 0 -> {
                    parts.add(body.substring(start, i).trim())
                    start = i + 1
                }
            }
        }
        parts.add(body.substring(start).trim())
        return parts
    }

    private fun parseAtom(value: String): Any {
        val unquoted = unquote(value)
        return when (unquoted.lowercase()) {
            "true" -> true
            "false" -> false
            "null", "~" -> ""
            else -> unquoted.toIntOrNull() ?: unquoted
        }
    }

    private fun unquote(value: String): String {
        if (value.length >= 2 && value.first() == '"' && value.last() == '"') {
            return value.substring(1, value.length - 1)
                .replace("\\\"", "\"")
                .replace("\\n", "\n")
                .replace("\\t", "\t")
                .replace("\\\\", "\\")
        }
        if (value.length >= 2 && value.first() == '\'' && value.last() == '\'') {
            return value.substring(1, value.length - 1).replace("''", "'")
        }
        return value
    }

    private fun scalarText(value: Any?): String = when (value) {
        null -> ""
        is JSONObject, is JSONArray -> value.toString()
        else -> value.toString()
    }

    /** Рекурсивно превращает вложенные Map/List в JSONObject/JSONArray. */
    private fun mapToJson(value: Any?): Any? = when (value) {
        is Map<*, *> -> JSONObject().apply {
            value.forEach { (k, v) -> put(k.toString(), mapToJson(v)) }
        }
        is Iterable<*> -> JSONArray().apply { value.forEach { put(mapToJson(it)) } }
        is JSONArray -> JSONArray().apply {
            for (i in 0 until value.length()) put(mapToJson(value.opt(i)))
        }
        is JSONObject -> value
        else -> value
    }

    private fun positiveHex(hash: Int): String = java.lang.Integer.toHexString(hash).padStart(8, '0')

    /** Base64 с теми же fallback'ами, что и [LinkParser.decodeBase64]. */
    private fun decodeBase64(input: String): String? {
        val cleaned = input.trim().replace("\n", "").replace("\r", "").replace(" ", "")
        if (cleaned.isEmpty()) return null
        val hasUrlSafe = cleaned.any { it == '-' || it == '_' }
        val hasStandard = cleaned.any { it == '+' || it == '/' }
        val alphabets = when {
            hasUrlSafe && !hasStandard -> listOf(Base64.URL_SAFE)
            hasStandard && !hasUrlSafe -> listOf(0)
            else -> listOf(Base64.URL_SAFE, 0)
        }
        for (alphabet in alphabets) {
            for (flags in listOf(
                alphabet or Base64.NO_WRAP or Base64.NO_PADDING,
                alphabet or Base64.NO_WRAP,
                alphabet or Base64.DEFAULT
            )) {
                val bytes = runCatching { Base64.decode(cleaned, flags) }.getOrNull() ?: continue
                if (bytes.isEmpty()) continue
                val text = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull() ?: continue
                if (text.contains('\uFFFD')) continue
                val encoded = runCatching {
                    Base64.encodeToString(bytes, (flags and Base64.URL_SAFE) or Base64.NO_WRAP)
                }.getOrNull() ?: continue
                if (encoded.trimEnd('=') == cleaned.trimEnd('=')) return text
            }
        }
        return null
    }
}
