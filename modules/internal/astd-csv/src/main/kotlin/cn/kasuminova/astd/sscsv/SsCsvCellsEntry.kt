package cn.kasuminova.astd.sscsv

interface SsCsvCellsEntry : SsCsvEntry {
    fun toCells(header: List<String>): List<String?>
}
