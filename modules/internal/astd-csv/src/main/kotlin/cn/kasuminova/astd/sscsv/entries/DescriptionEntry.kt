package cn.kasuminova.astd.sscsv.entries

import cn.kasuminova.astd.sscsv.CsvTarget
import cn.kasuminova.astd.sscsv.SsCsvEntry

abstract class DescriptionEntry : SsCsvEntry {
    final override val target: CsvTarget = CsvTarget.DESCRIPTIONS

    abstract val id: String

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
