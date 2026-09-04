package cn.kasuminova.astd.sscsv

/**
 * Optional entry interface: output a row as ordered cells aligned with the runtime header.
 *
 * This is useful for migrating existing CSV rows losslessly (keep original formatting).
 */
interface SsCsvCellsEntry : SsCsvEntry {
    fun toCells(header: List<String>): List<String?>
}
