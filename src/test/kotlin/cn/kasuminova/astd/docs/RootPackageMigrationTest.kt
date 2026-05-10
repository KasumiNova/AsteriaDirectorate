package cn.kasuminova.astd.docs

import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RootPackageMigrationTest {
    private val repoRoot = Path(System.getProperty("user.dir"))
    private val sourceRoots = listOf("src/main/java", "src/main/kotlin", "src/test/kotlin")
    private val oldRootPackage = "cn.kasuminova." + "asteriadirectorate"

    @Test
    fun `main and test sources use new root package`() {
        val files = sourceRoots.flatMap { root ->
            val path = repoRoot.resolve(root)
            if (Files.exists(path)) Files.walk(path).use { stream -> stream.filter { it.isRegularFile() }.toList() } else emptyList()
        }

        val sourceText = files.joinToString("\n") { it.readText() }
        assertFalse(sourceText.contains(oldRootPackage), "source still contains old root package")
        assertTrue(sourceText.contains("cn.kasuminova.astd"), "source should contain new root package")
    }
}
