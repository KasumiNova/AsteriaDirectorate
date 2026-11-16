package cn.kasuminova.astd.sscsv.tooltip

import cn.kasuminova.astd.sscsv.GeneratedFile
import cn.kasuminova.astd.sscsv.SsCsvEntry
import cn.kasuminova.astd.sscsv.i18n.DuplicateStringKey
import cn.kasuminova.astd.sscsv.json.JsonWriter

internal object TooltipStringsExport {

    const val JSON_PATH = "data/strings/tooltip_strings.json"

    const val CATEGORY = "asteria_directorate"

    fun files(
        registry: TooltipRegistry,
        csvEntries: List<SsCsvEntry>,
        duplicateStringKeys: List<DuplicateStringKey>,
    ): List<GeneratedFile> {
        if (duplicateStringKeys.isNotEmpty()) {
            val detail = duplicateStringKeys.joinToString(separator = "\n") {
                "- ${it.key}: ${it.first} vs ${it.duplicate}"
            }
            throw TooltipTemplateException(
                "i18n string key declared twice; every tooltip key must keep a single source:\n$detail"
            )
        }

        registry.validateTargets(csvEntries)

        val root = linkedMapOf<String, Any?>(CATEGORY to registry.exportedStrings())
        return listOf(
            GeneratedFile(
                relativePath = JSON_PATH,
                content = JsonWriter.pretty(root) + "\n",
            )
        )
    }
}
