package cn.kasuminova.astd.docs

import cn.kasuminova.astd.testutil.RepoLayout
import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RootPackageMigrationTest {
    private val repoRoot = Path(System.getProperty("user.dir"))
    private val oldRootPackage = "cn.kasuminova." + "asteriadirectorate"

    @Test
    fun `main and test sources use new root package`() {
        // 模块化拆分后 main 源码分布于 modules/**（经 RepoLayout 汇总），测试源仍在各模块 src/test 与根 src/test。
        val testRoots = listOf(repoRoot.resolve("src/test/kotlin")) + repoRoot.resolve("modules").let { modules ->
            if (Files.isDirectory(modules)) {
                Files.walk(modules).use { stream ->
                    stream.filter { Files.isDirectory(it) && it.fileName.toString() == "test" && it.parent.fileName.toString() == "src" }.toList()
                }
            } else {
                emptyList()
            }
        }
        val files = (RepoLayout.mainSourceRoots + testRoots).flatMap { root ->
            if (Files.exists(root)) Files.walk(root).use { stream -> stream.filter { it.isRegularFile() }.toList() } else emptyList()
        }

        val sourceText = files.joinToString("\n") { it.readText() }
        assertFalse(sourceText.contains(oldRootPackage), "source still contains old root package")
        assertTrue(sourceText.contains("cn.kasuminova.astd"), "source should contain new root package")
    }
}
