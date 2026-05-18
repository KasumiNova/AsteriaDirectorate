package cn.kasuminova.astd.docs

import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertTrue

class PackageStructureGuidelinesTest {
    private val repoRoot = Path(System.getProperty("user.dir"))

    @Test
    fun `package structure skill exists and is indexed`() {
        val skillPath = repoRoot.resolve(".agents/skills/package-structure-guidelines/SKILL.md")
        val indexPath = repoRoot.resolve(".agents/skills/00-skill-index/SKILL.md")

        assertTrue(skillPath.exists(), "package structure skill file should exist")
        assertTrue(indexPath.readText().contains("package-structure-guidelines"), "skill index should reference package structure skill")
    }

    @Test
    fun `package structure skill contains required package conventions`() {
        val text = repoRoot.resolve(".agents/skills/package-structure-guidelines/SKILL.md").readText()
        val required = listOf(
            "cn.kasuminova.astd",
            "astd.internal",
            "astd.renderer",
            "astd.combat",
            "combat.effect.base",
            "combat.effect.generic",
            "combat.effect.arc",
            "combat.effect.lens",
            "combat.shipsystems.base",
            "combat.skills.base",
            "combat.hullmods.base",
            "combat.affix.base",
        )

        for (needle in required) {
            assertTrue(text.contains(needle), "missing convention: $needle")
        }
    }
}
