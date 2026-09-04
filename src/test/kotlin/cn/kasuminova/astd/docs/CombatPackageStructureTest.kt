package cn.kasuminova.astd.docs

import cn.kasuminova.astd.testutil.RepoLayout
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 包结构纪律：跨全部 main 源码根（根装配工程 + modules 下各模块，见 [RepoLayout]）断言包声明。
 * 模块是物理边界，包名是逻辑边界——拆分不改 FQN，故包断言与拆分前一致。
 */
class CombatPackageStructureTest {

    @Test
    fun `old top level business packages are migrated`() {
        val text = RepoLayout.readAllMainSourceText()
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
        val text = RepoLayout.readAllMainSourceText()
        val required = listOf(
            "package cn.kasuminova.astd.internal",
            "package cn.kasuminova.astd.renderer",
            "package cn.kasuminova.astd.combat.effect.base",
            "package cn.kasuminova.astd.combat.effect.generic",
            "package cn.kasuminova.astd.combat.effect.arc",
            "package cn.kasuminova.astd.combat.effect.psi",
            "package cn.kasuminova.astd.combat.shipsystems",
            "package cn.kasuminova.astd.combat.hullmods",
            "package cn.kasuminova.astd.combat.affix",
        )
        for (needle in required) {
            assertTrue(text.contains(needle), "missing target package: $needle")
        }
    }
}
