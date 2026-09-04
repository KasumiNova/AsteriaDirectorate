package cn.kasuminova.astd.docs

import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import cn.kasuminova.astd.testutil.RepoLayout
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuntimeScriptReferenceMigrationTest {
    private val repoRoot = Path(System.getProperty("user.dir"))
    private val oldRootPackage = "cn.kasuminova." + "asteriadirectorate"

    @Test
    fun `runtime script references use new root package`() {
        val roots = listOf(
            repoRoot.resolve("gradle.properties"),
            repoRoot.resolve("contents/data"),
            RepoLayout.astdCsvRoot.resolve("src/main/kotlin"),
        )
        val files = roots.flatMap { root ->
            if (Files.isDirectory(root)) Files.walk(root).use { stream -> stream.filter { it.isRegularFile() }.toList() } else listOf(root)
        }
        val text = files.joinToString("\n") { it.readText() }
        assertFalse(text.contains(oldRootPackage), "runtime references still contain old root package")
        // 模组元数据事实源已迁移到 SDG DSL（build.gradle.kts 的 sdg 块），gradle.properties 不再承载 mod.* 条目。
        val buildScript = repoRoot.resolve("build.gradle.kts").readText()
        assertTrue(
            buildScript.contains("modPlugin.set(\"cn.kasuminova.astd.AsteriaDirectoratePlugin\")"),
            "mod plugin should use new root package",
        )
    }
}
