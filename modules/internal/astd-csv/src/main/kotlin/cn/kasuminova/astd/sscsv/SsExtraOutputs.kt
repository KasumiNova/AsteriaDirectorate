package cn.kasuminova.astd.sscsv

data class GeneratedFile(
    val relativePath: String,
    val content: String,
)

interface SsExtraOutputs {
    fun extraFiles(): List<GeneratedFile>
}
