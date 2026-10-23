package cn.kasuminova.astd.sscsv

interface SsCsvEntry {
    val target: CsvTarget

    val key: String

    fun toRow(): Map<String, Any?>
}
