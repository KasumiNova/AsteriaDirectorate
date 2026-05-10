package cn.kasuminova.astd.sscsv.json

import java.math.BigDecimal

/**
 * Minimal JSON pretty writer for ss-csv extra outputs.
 *
 * Goals:
 * - no external deps
 * - stable formatting
 * - supports: null/boolean/number/string/map/list/array
 */
internal object JsonWriter {

    fun pretty(value: Any?, indent: String = "    "): String {
        return render(value, 0, indent)
    }

    private fun render(value: Any?, level: Int, indent: String): String = when (value) {
        null -> "null"
        is Boolean -> if (value) "true" else "false"
        is Number -> formatNumber(value)
        is String -> quote(value)
        is Map<*, *> -> renderObject(value, level, indent)
        is Iterable<*> -> renderArray(value.toList(), level, indent)
        is Array<*> -> renderArray(value.toList(), level, indent)
        is IntArray -> renderArray(value.toList(), level, indent)
        is FloatArray -> renderArray(value.toList(), level, indent)
        is DoubleArray -> renderArray(value.toList(), level, indent)
        is LongArray -> renderArray(value.toList(), level, indent)
        is ShortArray -> renderArray(value.toList(), level, indent)
        is ByteArray -> renderArray(value.toList(), level, indent)
        else -> quote(value.toString())
    }

    private fun renderObject(map: Map<*, *>, level: Int, indent: String): String {
        if (map.isEmpty()) return "{}"

        val sb = StringBuilder()
        sb.append("{")
        sb.append('\n')

        val pad = indent.repeat(level + 1)
        val entries = map.entries.toList()

        for (i in entries.indices) {
            val (kAny, v) = entries[i]
            val k = kAny?.toString() ?: "null"
            sb.append(pad)
            sb.append(quote(k))
            sb.append(": ")
            sb.append(render(v, level + 1, indent))
            if (i != entries.lastIndex) sb.append(',')
            sb.append('\n')
        }

        sb.append(indent.repeat(level))
        sb.append('}')
        return sb.toString()
    }

    private fun renderArray(list: List<*>, level: Int, indent: String): String {
        if (list.isEmpty()) return "[]"

        // Small primitive arrays stay in one line for readability (e.g. [6, 16], [255, 170, 230, 255])
        val allPrimitive = list.all { it == null || it is Number || it is Boolean || it is String }
        if (allPrimitive && list.size <= 8) {
            return "[" + list.joinToString(", ") { render(it, level + 1, indent) } + "]"
        }

        val sb = StringBuilder()
        sb.append('[')
        sb.append('\n')

        val pad = indent.repeat(level + 1)
        for (i in list.indices) {
            sb.append(pad)
            sb.append(render(list[i], level + 1, indent))
            if (i != list.lastIndex) sb.append(',')
            sb.append('\n')
        }

        sb.append(indent.repeat(level))
        sb.append(']')
        return sb.toString()
    }

    private fun quote(s: String): String = buildString {
        append('"')
        for (ch in s) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (ch.code in 0..0x1F) {
                        append("\\u")
                        append(ch.code.toString(16).padStart(4, '0'))
                    } else {
                        append(ch)
                    }
                }
            }
        }
        append('"')
    }

    private fun formatNumber(n: Number): String = when (n) {
        is Byte, is Short, is Int, is Long -> n.toString()
        is Float -> formatDouble(n.toDouble())
        is Double -> formatDouble(n)
        else -> n.toString()
    }

    private fun formatDouble(d: Double): String {
        if (d.isNaN() || d.isInfinite()) return "0"
        val s = BigDecimal.valueOf(d).stripTrailingZeros().toPlainString()
        return if (s == "-0") "0" else s
    }
}
