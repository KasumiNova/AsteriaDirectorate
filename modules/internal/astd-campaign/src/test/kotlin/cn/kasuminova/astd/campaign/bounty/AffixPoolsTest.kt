package cn.kasuminova.astd.campaign.bounty

import cn.kasuminova.astd.combat.affix.AffixRegistry
import cn.kasuminova.astd.combat.affix.AffixRegistry.AffixType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 词缀规则传递验证：[AffixRule] 数量取档（affixes.md v3 搭配表）与 [AffixPools] 抽取约束
 * （S/M/R 定量、同 ID 不叠加、互斥表、相位约束标记、R 先抽让位）。
 *
 * 抽取测试注入合成池（直接构造 [AffixRegistry.AffixDef]），不依赖游戏环境。
 */
class AffixPoolsTest {

    private fun def(id: String, type: AffixType, phaseOnly: Boolean = false) =
        AffixRegistry.AffixDef(
            id = id,
            catalog = "T-$id",
            type = type,
            nameKey = "affix.$id.name",
            hullModId = "astd_affix_$id",
            unlockAfterMain = 0,
            phaseOnly = phaseOnly,
        )

    /** 合成池：S×8 / M×6 / R×3，含真实互斥对与相位约束条目。 */
    private fun syntheticPool(): List<AffixRegistry.AffixDef> = listOf(
        def("ironclad_plating", AffixType.S),
        def("cryo_flux_network", AffixType.S),
        def("flux_coil_expansion", AffixType.S),
        def("polarized_shield", AffixType.S),
        def("engine_overclock", AffixType.S),
        def("dimensional_specialty", AffixType.S),
        def("phase_coil_tuning", AffixType.S, phaseOnly = true),
        def("phase_coil_detuning", AffixType.S, phaseOnly = true),
        def("recursive_targeting", AffixType.M),
        def("reactive_flux_armor", AffixType.M),
        def("pspace_diver", AffixType.M, phaseOnly = true),
        def("engine_flux_isolation", AffixType.M),
        def("swarm_coordination", AffixType.M),
        def("plasma_armor_shield", AffixType.M),
        def("grid_deepening", AffixType.R),
        def("aggressive_swarm_network", AffixType.R),
        def("singularity_drive", AffixType.R),
    )

    @Test
    fun `词缀不介入时数量为零`() {
        assertEquals(AffixCounts(0, 0, 0), AffixRule.NONE.counts(0f))
        assertEquals(AffixCounts(0, 0, 0), AffixRule.NONE.counts(1f))
        val pick = AffixPools.pick(AffixRule.NONE, k = 1f, seed = 42, pool = syntheticPool())
        assertTrue(pick.affixHullMods.isEmpty())
    }

    @Test
    fun `S M 数量按难度系数取档`() {
        // S 2~4、M 1~2（截断取档，与 AffixRegistry 口径一致）
        assertEquals(AffixCounts(2, 1, 0), AffixRule.STANDARD.counts(0f))
        assertEquals(AffixCounts(2, 1, 0), AffixRule.STANDARD.counts(0.4f))
        assertEquals(AffixCounts(3, 1, 0), AffixRule.STANDARD.counts(0.5f))
        assertEquals(AffixCounts(4, 2, 0), AffixRule.STANDARD.counts(1f))
        // 越界钳制
        assertEquals(AffixCounts(2, 1, 0), AffixRule.STANDARD.counts(-1f))
        assertEquals(AffixCounts(4, 2, 0), AffixRule.STANDARD.counts(2f))
    }

    @Test
    fun `R 型定量区间按 k 取档且受 rMax 封顶`() {
        val rule = AffixRule.withR(1, 2)
        assertEquals(1, rule.counts(0f).r)
        assertEquals(2, rule.counts(1f).r)
        assertEquals(2, AffixRule.withR(2, 2).counts(0f).r, "固定 R=2 的规则在 k=0 也应给 2 条")
        assertEquals(0, AffixRule.STANDARD.counts(1f).r, "未开放 R 的规则任何 k 都不给 R")
    }

    @Test
    fun `抽取结果定量且不重复`() {
        val pool = syntheticPool()
        for (seed in 0L..50L) {
            val pick = AffixPools.pick(AffixRule.STANDARD, k = 1f, seed = seed, pool = pool)
            assertEquals(6, pick.affixHullMods.size, "k=1 时应为 S4+M2")
            assertEquals(pick.affixHullMods.size, pick.affixHullMods.toSet().size, "同 ID 不允许叠加（seed=$seed）")
        }
    }

    @Test
    fun `互斥表生效：互斥对不同时出现`() {
        val pool = syntheticPool()
        for (seed in 0L..200L) {
            val pick = AffixPools.pick(AffixRule.withR(1, 2), k = 1f, seed = seed, pool = pool)
            val ids = pick.affixHullMods.map { it.removePrefix("astd_affix_") }.toSet()
            assertFalse(
                "cryo_flux_network" in ids && "flux_coil_expansion" in ids,
                "六相冰辐能网络与极限辐能线圈扩容互斥（seed=$seed）",
            )
            assertFalse(
                "phase_coil_tuning" in ids && "phase_coil_detuning" in ids,
                "相位线圈调谐与降频互斥（seed=$seed）",
            )
            assertFalse(
                "grid_deepening" in ids && "flux_coil_expansion" in ids,
                "电网深化升级与极限辐能线圈扩容互斥（seed=$seed）",
            )
        }
    }

    @Test
    fun `R 型定量保证：规则要求 2 条时必出 2 条 R`() {
        val pool = syntheticPool()
        for (seed in 0L..50L) {
            val pick = AffixPools.pick(AffixRule.withR(2, 2), k = 1f, seed = seed, pool = pool)
            val rCount = pick.affixHullMods.count { it.removePrefix("astd_affix_") in setOf("grid_deepening", "aggressive_swarm_network", "singularity_drive") }
            assertEquals(2, rCount, "第四章阶段三口径：R 固定 2 条（seed=$seed）")
            // 打满搭配表：S4 + M2 + R2 = 8
            assertEquals(8, pick.affixHullMods.size)
        }
    }

    @Test
    fun `相位约束词缀被标记为 phaseOnly`() {
        val pool = syntheticPool()
        for (seed in 0L..200L) {
            val pick = AffixPools.pick(AffixRule.STANDARD, k = 1f, seed = seed, pool = pool)
            for (hm in pick.phaseOnlyHullMods) {
                val id = hm.removePrefix("astd_affix_")
                assertTrue(id in setOf("phase_coil_tuning", "phase_coil_detuning", "pspace_diver"), "非相位约束词缀被误标：$id")
            }
            // phaseOnly 子集必须属于总集
            assertTrue(pick.affixHullMods.containsAll(pick.phaseOnlyHullMods))
        }
    }

    @Test
    fun `相同种子结果确定一致`() {
        val pool = syntheticPool()
        val a = AffixPools.pick(AffixRule.withR(1, 2), k = 0.7f, seed = 12345, pool = pool)
        val b = AffixPools.pick(AffixRule.withR(1, 2), k = 0.7f, seed = 12345, pool = pool)
        assertEquals(a.affixHullMods, b.affixHullMods)
        assertEquals(a.phaseOnlyHullMods, b.phaseOnlyHullMods)
    }
}
