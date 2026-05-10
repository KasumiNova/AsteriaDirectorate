package cn.kasuminova.astd.sscsv.gen

import cn.kasuminova.astd.sscsv.CsvCodec
import cn.kasuminova.astd.sscsv.CsvTarget
import cn.kasuminova.astd.sscsv.GeneratedFile
import cn.kasuminova.astd.sscsv.SsCsvCellsEntry
import cn.kasuminova.astd.sscsv.SsCsvEntry
import cn.kasuminova.astd.sscsv.SsExtraOutputs
import cn.kasuminova.astd.sscsv.annotations.SsCsvComment
import io.github.classgraph.ClassGraph
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

private data class GenArgs(
    val outDir: Path,
    val schemaDir: Path,
    val scanPkg: String,
    val commentMode: CommentMode,
)

private enum class CommentMode {
    INLINE,
    SIDECAR,
    BOTH,
    NONE;

    val inline: Boolean get() = this == INLINE || this == BOTH
    val sidecar: Boolean get() = this == SIDECAR || this == BOTH
}

fun main(raw: Array<String>) {
    val args = parseArgs(raw)
    val result = generate(args)
    println("Generated ${result.filesWritten} file(s) with ${result.entriesFound} entry/entries.")
}

private fun parseArgs(raw: Array<String>): GenArgs {
    fun requireValue(i: Int): String {
        if (i + 1 >= raw.size) error("Missing value after ${raw[i]}")
        return raw[i + 1]
    }

    var out: String? = null
    var schema: String? = null
    var scan: String? = null
    var comment: String? = null

    var i = 0
    while (i < raw.size) {
        when (raw[i]) {
            "--out" -> {
                out = requireValue(i)
                i += 2
            }
            "--schema" -> {
                schema = requireValue(i)
                i += 2
            }
            "--scan" -> {
                scan = requireValue(i)
                i += 2
            }
            "--comment" -> {
                comment = requireValue(i)
                i += 2
            }
            else -> error("Unknown arg: ${raw[i]}")
        }
    }

    val outDir = out?.let { Path.of(it) } ?: error("--out is required")
    val schemaDir = schema?.let { Path.of(it) } ?: error("--schema is required")
    val scanPkg = scan ?: error("--scan is required")

    val commentMode = when ((comment ?: "inline").trim().lowercase()) {
        "inline" -> CommentMode.INLINE
        "sidecar" -> CommentMode.SIDECAR
        "both" -> CommentMode.BOTH
        "none" -> CommentMode.NONE
        else -> error("Unknown --comment value: $comment (expected inline|sidecar|both|none)")
    }

    return GenArgs(outDir = outDir, schemaDir = schemaDir, scanPkg = scanPkg, commentMode = commentMode)
}

private data class GenResult(val entriesFound: Int, val filesWritten: Int)

private fun generate(args: GenArgs): GenResult {
    val entries = scanEntries(args.scanPkg)
    val grouped = entries.groupBy { it.target }

    var files = 0

    for ((target, list) in grouped) {
        val header = readHeader(args.schemaDir.resolve(target.headerSchemaFile))
        val sorted = list.sortedBy { it.key }

        // Validate duplicate keys (almost always duplicate ids)
        val dup = sorted.groupBy { it.key }.filterValues { it.size > 1 }
        if (dup.isNotEmpty()) {
            error("Duplicate keys for ${target.name}: ${dup.keys.joinToString(", ")}")
        }

        val outFile = args.outDir.resolve(target.outputPath)
        writeCsv(outFile, header, sorted, args.commentMode)
        files++

        // Sidecar comments (legacy/safe)
        if (args.commentMode.sidecar) {
            val commentLines = sorted.mapNotNull { e ->
                val c = e.javaClass.getAnnotation(SsCsvComment::class.java)?.value
                if (c.isNullOrBlank()) null else "- `${e.key}`: $c"
            }
            if (commentLines.isNotEmpty()) {
                val commentFile = args.outDir.resolve("_comments/${target.outputPath}.md")
                writeText(
                    commentFile,
                    buildString {
                        appendLine("# ${target.name} comments")
                        appendLine()
                        commentLines.forEach { appendLine(it) }
                    }
                )
                files++
            }
        }
    }

    val extraFiles = entries
        .filterIsInstance<SsExtraOutputs>()
        .flatMap { it.extraFiles() }

    files += writeExtraFiles(args.outDir, extraFiles)

    return GenResult(entriesFound = entries.size, filesWritten = files)
}

private fun scanEntries(scanPkg: String): List<SsCsvEntry> {
    ClassGraph()
        .enableClassInfo()
        .enableAnnotationInfo()
        .acceptPackages(scanPkg)
        .scan().use { scanResult ->
            val impls = scanResult.getClassesImplementing(SsCsvEntry::class.java.name)
            return impls
                .filter { it.isFinal }
                .mapNotNull { ci ->
                    val cls = ci.loadClass()
                    // Kotlin object instances have a public static INSTANCE field
                    val instance = try {
                        cls.getField("INSTANCE").get(null)
                    } catch (_: Throwable) {
                        null
                    }
                    instance as? SsCsvEntry
                }
        }
}

private fun readHeader(path: Path): List<String> {
    val line = Files.readAllLines(path, StandardCharsets.UTF_8).firstOrNull()
        ?: error("Empty header file: $path")
    return CsvCodec.parseCsvLine(line)
}

private fun writeCsv(path: Path, header: List<String>, entries: List<SsCsvEntry>, commentMode: CommentMode) {
    Files.createDirectories(path.parent)

    val lines = ArrayList<String>(entries.size + 1)
    lines += header.joinToString(",") { csvEscape(it) }

    for (e in entries) {
        if (commentMode.inline) {
            val c = e.javaClass.getAnnotation(SsCsvComment::class.java)?.value
            if (!c.isNullOrBlank()) {
                lines += commentCsvLine(header.size, "# ${e.key}: $c")
            }
        }

        val rowLine = when (e) {
            is SsCsvCellsEntry -> {
                val rawCells = e.toCells(header)
                if (rawCells.size != header.size) {
                    error("toCells() must return ${header.size} cells for ${e.key} (${e.target.name}), got ${rawCells.size}")
                }
                rawCells.joinToString(",") { cell -> csvEscape(cell ?: "") }
            }
            else -> {
                val row = e.toRow()
                val cells = header.map { col ->
                    val v = row[col]
                    csvEscape(cellToString(v))
                }
                cells.joinToString(",")
            }
        }

        lines += rowLine
    }

    Files.write(
        path,
        lines,
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE,
    )
}

private fun cellToString(v: Any?): String = when (v) {
    null -> ""
    is String -> v
    is Number -> formatNumber(v)
    else -> v.toString()
}

private fun formatNumber(n: Number): String = when (n) {
    is Byte, is Short, is Int, is Long -> n.toString()
    is Float -> formatDouble(n.toDouble())
    is Double -> formatDouble(n)
    else -> n.toString()
}

private fun formatDouble(d: Double): String {
    if (d.isNaN() || d.isInfinite()) return d.toString()
    // BigDecimal.valueOf(Double) uses a canonical string representation and is stable for common values.
    val s = BigDecimal.valueOf(d).stripTrailingZeros().toPlainString()
    return if (s == "-0") "0" else s
}

private fun writeText(path: Path, text: String) {
    Files.createDirectories(path.parent)
    Files.writeString(
        path,
        text,
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE,
    )
}

private fun csvEscape(s: String): String = CsvCodec.csvEscape(s)

private fun commentCsvLine(columnCount: Int, comment: String): String {
    // Starsector often supports comment lines starting with '#'.
    // Ensure the line *actually* starts with '#' (no CSV quoting prefix), otherwise it may not be treated as a comment.
    // Also replace commas to keep the "name-only" comment-row shape stable.
    val normalized = comment
        .replace('\r', ' ')
        .replace('\n', ' ')
        .replace(',', ';')
        .trim()
        .let { if (it.startsWith('#')) it else "# $it" }

    if (columnCount <= 1) return normalized
    return normalized + ",".repeat(columnCount - 1)
}

private fun writeExtraFiles(outDir: Path, files: List<GeneratedFile>): Int {
    if (files.isEmpty()) return 0

    val dup = files.groupBy { it.relativePath }.filterValues { it.size > 1 }
    if (dup.isNotEmpty()) {
        error("Duplicate extra file outputs: ${dup.keys.joinToString(", ")}")
    }

    for (f in files) {
        val rel = f.relativePath.replace('\\', '/')
        val out = outDir.resolve(rel).normalize()
        if (!out.startsWith(outDir.normalize())) {
            error("Refusing to write outside outDir: ${f.relativePath}")
        }
        writeText(out, f.content)
    }

    return files.size
}
