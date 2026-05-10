package cn.kasuminova.astd.sscsv

/**
 * Base contract for a Starsector CSV entry.
 *
 * New data should be added by creating a Kotlin `object` that extends one of the
 * abstract entry types (e.g. ShipSystemEntry) and filling fields.
 */
interface SsCsvEntry {
    val target: CsvTarget

    /**
     * A stable key used for sorting and duplicate detection.
     * Usually this should match the `id` column.
     */
    val key: String

    /**
     * Return a map from column name -> value.
     * Missing columns will be emitted as empty cells.
     */
    fun toRow(): Map<String, Any?>
}
