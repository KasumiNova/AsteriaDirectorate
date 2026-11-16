package cn.kasuminova.astd.sscsv.tooltip

internal object NamedTemplate {

    private const val SLOT = "{%s}"

    fun variables(text: String): List<String> {
        if (text.indexOf('%') < 0) return emptyList()
        val names = mutableListOf<String>()
        var i = 0
        while (i < text.length) {
            if (text[i] == '%') {
                if (i + 1 < text.length && text[i + 1] == '%') {
                    i += 2
                    continue
                }
                val end = closingPercent(text, i + 1)
                if (end >= 0) {
                    names += text.substring(i + 1, end)
                    i = end + 1
                    continue
                }
            }
            i++
        }
        return names
    }

    /** `collapseEscapes` 为真时把模板中的 `%%` 归一为 `%`；武器原生正文按字面渲染，需保持原样。 */
    fun expand(text: String, values: Map<String, String>, collapseEscapes: Boolean): String {
        if (text.indexOf('%') < 0) return text
        val out = StringBuilder(text.length + 8)
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch == '%') {
                if (i + 1 < text.length && text[i + 1] == '%') {
                    if (collapseEscapes) out.append('%') else out.append("%%")
                    i += 2
                    continue
                }
                val end = closingPercent(text, i + 1)
                if (end >= 0) {
                    val name = text.substring(i + 1, end)
                    val value = values[name]
                    if (value != null) {
                        out.append(value)
                    } else {
                        out.append(text, i, end + 1)
                    }
                    i = end + 1
                    continue
                }
            }
            out.append(ch)
            i++
        }
        return out.toString()
    }

    fun slotCount(text: String): Int {
        var count = 0
        var from = 0
        while (true) {
            val at = text.indexOf(SLOT, from)
            if (at < 0) return count
            count++
            from = at + SLOT.length
        }
    }

    private fun closingPercent(text: String, start: Int): Int {
        if (start >= text.length) return -1
        if (!text[start].isAsciiLetter()) return -1
        var i = start + 1
        while (i < text.length) {
            val ch = text[i]
            if (ch == '%') return i
            if (!ch.isAsciiLetter() && ch !in '0'..'9' && ch != '_') return -1
            i++
        }
        return -1
    }

    private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'

}
