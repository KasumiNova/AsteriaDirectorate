package cn.kasuminova.astd.sscsv.entries

import cn.kasuminova.astd.sscsv.CsvTarget
import cn.kasuminova.astd.sscsv.SsCsvEntry

/**
 * Starsector `data/strings/descriptions.csv` 的标准条目基类。
 *
 * 说明：
 * - `id` 同时作为 CSV 的 key。
 * - `text1..text4` 通常会在武器/系统/舰船的详情面板顶部作为“描述段落”显示。
 * - `text5` 对舰船系统常用于额外数值说明；为空时会按 CSV 默认留空。
 * - 为避免 UI 内部的格式化（String.format）踩坑，建议在描述文本中优先使用全角百分号 `％`。
 */
abstract class DescriptionEntry : SsCsvEntry {
    final override val target: CsvTarget = CsvTarget.DESCRIPTIONS

    /** 描述条目 id（同时作为 key）。通常与 weapon/system id 一致。 */
    abstract val id: String

    /** 描述类型（descriptions.csv 的 type 列），如：WEAPON / SHIP_SYSTEM。 */
    abstract val type: String

    open val text1: String = ""
    open val text2: String = ""
    open val text3: String = ""
    open val text4: String = ""
    open val text5: String = ""
    open val notes: String = ""

    final override val key: String get() = id

    final override fun toRow(): Map<String, Any?> = linkedMapOf(
        "id" to id,
        "type" to type,
        "text1" to text1,
        "text2" to text2,
        "text3" to text3,
        "text4" to text4,
        "text5" to text5,
        "notes" to notes,
    )
}
