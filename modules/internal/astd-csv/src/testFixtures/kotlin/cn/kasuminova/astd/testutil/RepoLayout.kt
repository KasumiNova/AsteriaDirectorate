package cn.kasuminova.astd.testutil

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

object RepoLayout {

    private val repoRoot: Path = Path.of(System.getProperty("user.dir"))

    val astdCsvRoot: Path = repoRoot.resolve("modules/internal/astd-csv")

    val automationContentsRoot: Path = repoRoot.resolve("modules/internal/astd-automation/contents")

    val mainSourceRoots: List<Path> by lazy {
        val roots = mutableListOf<Path>()
        listOf("src/main/java", "src/main/kotlin").forEach { rel ->
            repoRoot.resolve(rel).takeIf(Files::isDirectory)?.let(roots::add)
        }
        val modulesDir = repoRoot.resolve("modules")
        if (Files.isDirectory(modulesDir)) {
            Files.list(modulesDir).use { groups ->
                groups.forEach { group ->
                    if (!Files.isDirectory(group)) return@forEach
                    Files.list(group).use { modules ->
                        modules.forEach { module ->
                            listOf("src/main/kotlin", "src/main/java").forEach { rel ->
                                module.resolve(rel).takeIf(Files::isDirectory)?.let(roots::add)
                            }
                        }
                    }
                }
            }
        }
        roots.sorted()
    }

    fun readAllMainSourceText(): String = mainSourceRoots.flatMap { root ->
        Files.walk(root).use { stream -> stream.filter { it.isRegularFile() }.toList() }
    }.joinToString("\n") { it.readText() }

    fun mainSourceFileOf(className: String): Path? {
        val relative = className.replace('.', '/') + ".kt"
        return mainSourceRoots.map { it.resolve(relative) }.firstOrNull(Files::exists)
    }

    fun mainSourceFile(packageRelativePath: String): Path? = mainSourceRoots
        .map { it.resolve("cn/kasuminova/astd").resolve(packageRelativePath) }
        .firstOrNull(Files::exists)
}
