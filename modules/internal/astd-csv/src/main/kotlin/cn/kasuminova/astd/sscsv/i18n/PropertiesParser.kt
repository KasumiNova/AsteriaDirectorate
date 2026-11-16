package cn.kasuminova.astd.sscsv.i18n

import java.io.Reader

internal data class ParsedProperties(
    val values: Map<String, String>,
    val lineNumbers: Map<String, Int>,
    val duplicates: List<DuplicateStringKey>,
)

/** [first] 与 [duplicate] 为 `文件:行号`，同一文件内重复时文件相同。 */
internal data class DuplicateStringKey(
    val key: String,
    val first: String,
    val duplicate: String,
)

internal fun propertyLocation(source: String, line: Int): String = "$source:$line"

internal object PropertiesParser {

    fun parse(source: String, reader: Reader): ParsedProperties {
        val values = LinkedHashMap<String, String>()
        val lineNumbers = HashMap<String, Int>()
        val duplicates = mutableListOf<DuplicateStringKey>()

        var lineNumber = 0
        var pending: StringBuilder? = null
        var pendingLine = 0

        fun record(text: String, line: Int) {
            val (key, value) = splitEntry(text, source, line)
            val previous = lineNumbers.put(key, line)
            if (previous != null) {
                duplicates += DuplicateStringKey(
                    key = key,
                    first = propertyLocation(source, previous),
                    duplicate = propertyLocation(source, line),
                )
            }
            values[key] = value
        }

        reader.forEachLine { raw ->
            lineNumber++
            val line = raw.substring(skipPropertyWhitespace(raw))
            val continuing = pending != null

            if (!continuing) {
                if (line.isEmpty() || line[0] == '#' || line[0] == '!') return@forEachLine
                pending = StringBuilder()
                pendingLine = lineNumber
            }

            val buffer = pending ?: return@forEachLine
            if (endsWithContinuation(line)) {
                buffer.append(line, 0, line.length - 1)
                return@forEachLine
            }

            buffer.append(line)
            pending = null
            record(buffer.toString(), pendingLine)
        }

        pending?.let { record(it.toString(), pendingLine) }

        return ParsedProperties(values = values, lineNumbers = lineNumbers, duplicates = duplicates)
    }

    private fun isPropertyWhitespace(ch: Char): Boolean = ch == ' ' || ch == '\t' || ch == '\u000C'

    private fun skipPropertyWhitespace(text: String, from: Int = 0): Int {
        var i = from
        while (i < text.length && isPropertyWhitespace(text[i])) i++
        return i
    }

    private fun endsWithContinuation(line: String): Boolean {
        var backslashes = 0
        var i = line.length - 1
        while (i >= 0 && line[i] == '\\') {
            backslashes++
            i--
        }
        return backslashes % 2 == 1
    }

    private fun splitEntry(text: String, source: String, line: Int): Pair<String, String> {
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch == '\\') {
                i += 2
                continue
            }
            if (ch == '=' || ch == ':' || isPropertyWhitespace(ch)) break
            i++
        }

        val key = unescape(text.substring(0, i))
        if (key.isEmpty()) {
            throw TooltipI18nException("i18n entry has no key: source=$source line=$line")
        }

        var valueStart = skipPropertyWhitespace(text, i)
        if (valueStart < text.length && (text[valueStart] == '=' || text[valueStart] == ':')) {
            valueStart = skipPropertyWhitespace(text, valueStart + 1)
        }

        return key to unescape(text.substring(valueStart))
    }

    private fun isHexDigit(ch: Char): Boolean =
        ch in '0'..'9' || ch in 'a'..'f' || ch in 'A'..'F'

    private fun unescape(text: String): String {
        if (text.indexOf('\\') < 0) return text

        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch != '\\') {
                out.append(ch)
                i++
                continue
            }
            if (i + 1 >= text.length) {
                out.append('\\')
                break
            }
            when (val escaped = text[i + 1]) {
                't' -> out.append('\t')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                'f' -> out.append('\u000C')
                'u' -> {
                    if (i + 5 >= text.length) {
                        throw TooltipI18nException("i18n has truncated \\u escape: source=$text")
                    }
                    val hex = text.substring(i + 2, i + 6)
                    if (!hex.all(::isHexDigit)) {
                        throw TooltipI18nException("i18n has invalid \\u escape: \\u$hex")
                    }
                    out.append(hex.toInt(16).toChar())
                    i += 6
                    continue
                }

                else -> out.append(escaped)
            }
            i += 2
        }
        return out.toString()
    }
}

internal class TooltipI18nException(message: String) : IllegalStateException(message)
