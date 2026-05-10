package cn.kasuminova.astd.docs

import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CombatPackageStructureTest {
    private val repoRoot = Path(System.getProperty("user.dir"))
    private val srcMain = listOf(repoRoot.resolve("src/main/java"), repoRoot.resolve("src/main/kotlin"))

    @Test
    fun `old top level business packages are migrated`() {
        val text = readMainSourceText()
        val forbidden = listOf(
            "package cn.kasuminova.astd.weapons",
            "package cn.kasuminova.astd.shipsystems",
            "package cn.kasuminova.astd.skills",
            "package cn.kasuminova.astd.hullmods",
            "package cn.kasuminova.astd.combat.vfx",
        )
        for (needle in forbidden) {
            assertFalse(text.contains(needle), "old package remains: $needle")
        }
    }

    @Test
    fun `new domain packages exist`() {
        val text = readMainSourceText()
        val required = listOf(
            "package cn.kasuminova.astd.internal",
            "package cn.kasuminova.astd.renderer",
            "package cn.kasuminova.astd.combat.effect.base",
            "package cn.kasuminova.astd.combat.effect.generic",
            "package cn.kasuminova.astd.combat.effect.arc",
            "package cn.kasuminova.astd.combat.effect.lens",
            "package cn.kasuminova.astd.combat.effect.psi",
            "package cn.kasuminova.astd.combat.shipsystems",
            "package cn.kasuminova.astd.combat.skills",
            "package cn.kasuminova.astd.combat.hullmods",
            "package cn.kasuminova.astd.combat.affix",
        )
        for (needle in required) {
            assertTrue(text.contains(needle), "missing target package: $needle")
        }
    }

    private fun readMainSourceText(): String = srcMain.flatMap { root ->
        if (Files.exists(root)) Files.walk(root).use { stream -> stream.filter { it.isRegularFile() }.toList() } else emptyList()
    }.joinToString("\n") { it.readText() }
}
