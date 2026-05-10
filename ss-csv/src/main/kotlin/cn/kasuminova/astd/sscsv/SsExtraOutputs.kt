package cn.kasuminova.astd.sscsv

/** A generated extra file (relative to the selected output directory). */
data class GeneratedFile(
    val relativePath: String,
    val content: String,
)

/**
 * Optional entry interface: generate extra files besides CSV rows.
 *
 * Typical use cases:
 * - ship systems: `ship_systems.csv` + `${id}.system`
 * - weapons: `weapon_data.csv` + `${id}.wpn` (+ projectiles)
 * - hulls: `ship_data.csv` + `${id}.ship` (+ variants)
 */
interface SsExtraOutputs {
    fun extraFiles(): List<GeneratedFile>
}
