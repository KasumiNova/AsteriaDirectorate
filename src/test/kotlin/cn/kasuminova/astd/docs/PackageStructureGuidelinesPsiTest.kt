package cn.kasuminova.astd.docs

import kotlin.io.path.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertTrue

class PackageStructureGuidelinesPsiTest {
    private val repoRoot = Path(System.getProperty("user.dir"))

    @Test
    fun `package structure skill documents psi combat effect package`() {
        val text = repoRoot.resolve(".github/skills/package-structure-guidelines/SKILL.md").readText()
        assertTrue(text.contains("combat.effect.psi"), "psi effect package should be documented")
    }
}
