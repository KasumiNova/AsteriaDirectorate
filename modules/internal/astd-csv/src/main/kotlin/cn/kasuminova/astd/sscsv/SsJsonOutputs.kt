package cn.kasuminova.astd.sscsv

import cn.kasuminova.astd.sscsv.json.JsonWriter

/** A generated JSON-ish extra file (relative to the selected output directory). */
data class GeneratedJsonFile(
    val relativePath: String,
    /** Can be Map/List/String/Number/Boolean/null (see [JsonWriter]). */
    val jsonValue: Any?,
)

/**
 * Optional entry interface: generate JSON/proj/system/... files in addition to CSV rows.
 *
 * This is built on top of [SsExtraOutputs] to keep [SsCsvGenerator] unchanged.
 */
interface SsJsonOutputs : SsExtraOutputs {

    fun jsonExtraFiles(): List<GeneratedJsonFile>

    override fun extraFiles(): List<GeneratedFile> {
        val jsonFiles = jsonExtraFiles()
        if (jsonFiles.isEmpty()) return emptyList()

        return jsonFiles.map { f ->
            // Keep a trailing newline to match typical data files and make diffs cleaner.
            GeneratedFile(
                relativePath = f.relativePath,
                content = JsonWriter.pretty(f.jsonValue) + "\n",
            )
        }
    }
}
