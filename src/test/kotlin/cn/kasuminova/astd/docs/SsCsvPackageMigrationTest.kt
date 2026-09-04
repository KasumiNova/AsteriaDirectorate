package cn.kasuminova.astd.docs

import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import cn.kasuminova.astd.testutil.RepoLayout
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SsCsvPackageMigrationTest {
    private val repoRoot = Path(System.getProperty("user.dir"))
    private val oldSsCsvPackage = "cn.kasuminova." + "asteriadirectorate.sscsv"

    @Test
    fun `ss csv source and gradle use new package`() {
        val files = listOf(RepoLayout.astdCsvRoot.resolve("src/main/kotlin"), RepoLayout.astdCsvRoot.resolve("build.gradle.kts")).flatMap { root ->
            if (Files.isDirectory(root)) Files.walk(root).use { stream -> stream.filter { it.isRegularFile() }.toList() } else listOf(root)
        }
        val text = files.joinToString("\n") { it.readText() }
        assertFalse(text.contains(oldSsCsvPackage), "ss-csv still contains old package")
        assertTrue(text.contains("cn.kasuminova.astd.sscsv"), "ss-csv should contain new package")
    }
}
