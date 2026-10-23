package cn.kasuminova.astd.sscsv

import cn.kasuminova.astd.sscsv.json.JsonWriter

data class GeneratedJsonFile(
    val relativePath: String,
    val jsonValue: Any?,
)

interface SsJsonOutputs : SsExtraOutputs {

    fun jsonExtraFiles(): List<GeneratedJsonFile>

    override fun extraFiles(): List<GeneratedFile> {
        val jsonFiles = jsonExtraFiles()
        if (jsonFiles.isEmpty()) return emptyList()

        return jsonFiles.map { f ->
            GeneratedFile(
                relativePath = f.relativePath,
                content = JsonWriter.pretty(f.jsonValue) + "\n",
            )
        }
    }
}
