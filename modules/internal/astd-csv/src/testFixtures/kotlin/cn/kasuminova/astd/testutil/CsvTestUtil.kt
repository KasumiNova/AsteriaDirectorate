package cn.kasuminova.astd.testutil

import java.nio.file.Files
import java.nio.file.Path

object CsvTestUtil {
    fun readRowsById(path: Path): Map<String, Map<String, String>> {
        val records = parseRecords(Files.readString(path))
            .filter { row -> row.any { it.isNotBlank() } }
            .filterNot { row -> row.firstOrNull()?.trimStart()?.startsWith("#") == true }
        require(records.isNotEmpty()) { "CSV has no records: $path" }
        val header = records.first()
        require("id" in header) { "CSV has no id column: $path" }
        return records.drop(1).associate { cells ->
            require(cells.size <= header.size) {
                "CSV row has more cells than header in $path: ${cells.joinToString(",")}"
            }
            val row = header.mapIndexed { idx, column -> column to cells.getOrElse(idx) { "" } }.toMap()
            row.getValue("id") to row
        }
    }

    private fun parseRecords(text: String): List<List<String>> {
        val records = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val cell = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            when {
                ch == '"' && inQuotes && i + 1 < text.length && text[i + 1] == '"' -> {
                    cell.append('"')
                    i++
                }
                ch == '"' -> inQuotes = !inQuotes
                ch == ',' && !inQuotes -> {
                    row += cell.toString()
                    cell.setLength(0)
                }
                (ch == '\n' || ch == '\r') && !inQuotes -> {
                    row += cell.toString()
                    cell.setLength(0)
                    records += row.toList()
                    row.clear()
                    if (ch == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                }
                else -> cell.append(ch)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) {
            row += cell.toString()
            records += row.toList()
        }
        require(!inQuotes) { "CSV has unterminated quoted field" }
        return records
    }
}
