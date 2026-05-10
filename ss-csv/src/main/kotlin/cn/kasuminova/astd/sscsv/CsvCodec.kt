package cn.kasuminova.astd.sscsv

/**
 * Small CSV helpers for Starsector-style CSV.
 *
 * - Header parsing supports quoted cells.
 * - Row writing uses RFC4180-ish escaping.
 */
object CsvCodec {
    /** Minimal RFC4180-ish line parser (quotes supported). */
    fun parseCsvLine(line: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                inQuotes && ch == '"' -> {
                    val next = if (i + 1 < line.length) line[i + 1] else null
                    if (next == '"') {
                        sb.append('"')
                        i += 2
                        continue
                    } else {
                        inQuotes = false
                        i++
                        continue
                    }
                }
                !inQuotes && ch == '"' -> {
                    inQuotes = true
                    i++
                    continue
                }
                !inQuotes && ch == ',' -> {
                    out += sb.toString()
                    sb.setLength(0)
                    i++
                    continue
                }
                else -> {
                    sb.append(ch)
                    i++
                }
            }
        }
        out += sb.toString()
        return out
    }

    fun csvEscape(s: String): String {
        val needs = s.indexOfAny(charArrayOf(',', '"', '\n', '\r')) >= 0
        if (!needs) return s
        return buildString {
            append('"')
            for (ch in s) {
                if (ch == '"') append("\"\"") else append(ch)
            }
            append('"')
        }
    }
}
