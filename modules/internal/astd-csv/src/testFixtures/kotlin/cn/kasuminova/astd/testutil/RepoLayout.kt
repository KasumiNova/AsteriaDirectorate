package cn.kasuminova.astd.testutil

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * 仓库多模块布局定位器：模块化拆分后 main 源码分布于 `modules/{api,internal}/<模块>/src/main/kotlin`
 * 与根装配工程 `src/main/{java,kotlin}`，纪律测试统一经本类定位源码文件，不再硬编码单一路径。
 *
 * 约定：测试运行工作目录钉在仓库根（根 build.gradle.kts 的 Test.workingDir 约定块保证）。
 */
object RepoLayout {

    private val repoRoot: Path = Path.of(System.getProperty("user.dir"))

    /** astd-csv（原 ss-csv）模块根。 */
    val astdCsvRoot: Path = repoRoot.resolve("modules/internal/astd-csv")

    /** astd-automation 模块的 contents 根（测试战役/自动化场景定义；release 打包时排除）。 */
    val automationContentsRoot: Path = repoRoot.resolve("modules/internal/astd-automation/contents")

    /** 全部 main 源码根（根装配工程 + modules 两层下的各模块），按路径排序保证稳定。 */
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

    /** 全部 main 源码文本拼接（包结构断言用）。 */
    fun readAllMainSourceText(): String = mainSourceRoots.flatMap { root ->
        Files.walk(root).use { stream -> stream.filter { it.isRegularFile() }.toList() }
    }.joinToString("\n") { it.readText() }

    /**
     * 按 FQN 定位 main 源码文件：文件名与主类同名（仓库现行约定），
     * 在所有 main 源码根下找 `cn/kasuminova/astd/<其余路径>.kt`。
     */
    fun mainSourceFileOf(className: String): Path? {
        val relative = className.replace('.', '/') + ".kt"
        return mainSourceRoots.map { it.resolve(relative) }.firstOrNull(Files::exists)
    }

    /** 按「包相对路径」定位 main 源码文件（如 `combat/hullmods/arc/Xxx.kt`）。 */
    fun mainSourceFile(packageRelativePath: String): Path? = mainSourceRoots
        .map { it.resolve("cn/kasuminova/astd").resolve(packageRelativePath) }
        .firstOrNull(Files::exists)
}
