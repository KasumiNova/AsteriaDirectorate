package cn.kasuminova.astd.combat.affix

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AffixRegistryTest {

    @Test
    fun `编目完整且编号与类型一一对应`() {
        assertEquals(17, AffixRegistry.all.size)
        assertEquals(17, AffixRegistry.all.map { it.id }.distinct().size)
        assertEquals(17, AffixRegistry.all.map { it.hullModId }.distinct().size)

        val byType = AffixRegistry.all.groupBy { it.type }
        assertEquals(8, byType.getValue(AffixRegistry.AffixType.S).size)
        assertEquals(6, byType.getValue(AffixRegistry.AffixType.M).size)
        assertEquals(3, byType.getValue(AffixRegistry.AffixType.R).size)

        AffixRegistry.all.forEachIndexed { index, def ->
            val expectedNo = "${def.type.name}-%02d".format(index + 1)
            assertEquals(expectedNo, def.catalogNo, "编目号应与类型+序号一致：${def.id}")
            assertEquals(def.id, def.hullModId, "词缀 id 与 hullModId 一致：${def.id}")
        }

        assertEquals(
            setOf(
                AffixRegistry.ID_PHASE_COIL_TUNING,
                AffixRegistry.ID_PHASE_COIL_DETUNING,
                AffixRegistry.ID_PSPACE_DIVER,
            ),
            AffixRegistry.all.filter { it.phaseOnly }.map { it.id }.toSet(),
        )
    }

    @Test
    fun `数量搭配表按难度系数取区间端点与中点`() {
        assertEquals(AffixRegistry.AffixSlots(2, 1, 0), AffixRegistry.slotCounts(1.0f, allowR = false))
        assertEquals(AffixRegistry.AffixSlots(3, 1, 0), AffixRegistry.slotCounts(2.0f, allowR = false))
        assertEquals(AffixRegistry.AffixSlots(3, 2, 0), AffixRegistry.slotCounts(3.0f, allowR = false))
        assertEquals(AffixRegistry.AffixSlots(4, 2, 0), AffixRegistry.slotCounts(5.0f, allowR = false))

        assertEquals(1, AffixRegistry.slotCounts(1.0f, allowR = true).rSlots)
        assertEquals(2, AffixRegistry.slotCounts(5.0f, allowR = true).rSlots)

        assertEquals(AffixRegistry.slotCounts(1.0f, true), AffixRegistry.slotCounts(0.2f, true))
        assertEquals(AffixRegistry.slotCounts(5.0f, true), AffixRegistry.slotCounts(99f, true))
    }

    @Test
    fun `抽取结果类型数量不越出搭配表`() {
        for (k in listOf(1f, 2f, 3f, 4f, 5f)) {
            val slots = AffixRegistry.slotCounts(k, allowR = true)
            for (seed in 0L until 50L) {
                val picked = AffixRegistry.pickAffixes(k, allowR = true, allowPhase = true, seed = seed)
                val byType = picked.groupBy { it.type }
                assertTrue((byType[AffixRegistry.AffixType.S]?.size ?: 0) <= slots.sCount, "S 超出搭配表：k=$k seed=$seed")
                assertTrue((byType[AffixRegistry.AffixType.M]?.size ?: 0) <= slots.mCount, "M 超出搭配表：k=$k seed=$seed")
                assertTrue((byType[AffixRegistry.AffixType.R]?.size ?: 0) <= slots.rSlots, "R 超出槽位：k=$k seed=$seed")
            }
        }
    }

    @Test
    fun `互斥表三对在同一次抽取中不共存`() {
        for (seed in 0L until 500L) {
            val picked = AffixRegistry.pickAffixes(kS = 5f, allowR = true, allowPhase = true, seed = seed)
            val ids = picked.map { it.id }
            assertEquals(ids.size, ids.distinct().size, "同 ID 不叠加：seed=$seed")
            for (pair in AffixRegistry.EXCLUSIVE_PAIRS) {
                assertTrue(ids.count { it in pair } <= 1, "互斥对共存 $pair：seed=$seed -> $ids")
            }
        }
    }

    @Test
    fun `相位约束在抽取时强制`() {
        for (seed in 0L until 500L) {
            val picked = AffixRegistry.pickAffixes(kS = 5f, allowR = true, allowPhase = false, seed = seed)
            assertTrue(picked.none { it.phaseOnly }, "无相位舰队抽到了相位限定词缀：seed=$seed")
        }
        val anyPhase = (0L until 200L).any { seed ->
            AffixRegistry.pickAffixes(kS = 5f, allowR = true, allowPhase = true, seed = seed).any { it.phaseOnly }
        }
        assertTrue(anyPhase, "相位舰队应能抽到相位限定词缀")
    }

    @Test
    fun `allowR 关闭时绝不出现 R 型`() {
        for (seed in 0L until 500L) {
            val picked = AffixRegistry.pickAffixes(kS = 5f, allowR = false, allowPhase = true, seed = seed)
            assertTrue(picked.none { it.type == AffixRegistry.AffixType.R }, "allowR=false 抽到了 R 型：seed=$seed")
        }
    }

    @Test
    fun `allowR 开启时 R 型按低权重出现且高系数下必然出现`() {
        var rCount = 0
        for (seed in 0L until 200L) {
            val picked = AffixRegistry.pickAffixes(kS = 5f, allowR = true, allowPhase = true, seed = seed)
            rCount += picked.count { it.type == AffixRegistry.AffixType.R }
        }
        assertTrue(rCount > 0, "k=5 且 allowR=true 时 R 槽位必然填充")

        var lowK = 0
        val trials = 2000
        for (seed in 0L until trials.toLong()) {
            val picked = AffixRegistry.pickAffixes(kS = 1f, allowR = true, allowPhase = true, seed = seed)
            lowK += picked.count { it.type == AffixRegistry.AffixType.R }
        }
        val rate = lowK.toFloat() / trials
        assertTrue(rate in 0.2f..0.5f, "k=1 时 R 出现率应落在 0.35 附近，实际 $rate")
    }

    @Test
    fun `同 seed 同参数结果完全确定`() {
        val a = AffixRegistry.pickAffixes(kS = 3.7f, allowR = true, allowPhase = true, seed = 42L)
        val b = AffixRegistry.pickAffixes(kS = 3.7f, allowR = true, allowPhase = true, seed = 42L)
        assertEquals(a.map { it.id }, b.map { it.id })
        assertTrue(a.isNotEmpty())
    }

    @Test
    fun `固定表校验覆盖未知重复互斥与相位限定`() {
        assertEquals(
            emptyList(),
            AffixRegistry.validateFixedTable(
                listOf(AffixRegistry.ID_IRONCLAD_PLATING, AffixRegistry.ID_RECURSIVE_TARGETING),
            ),
        )

        assertTrue(
            AffixRegistry.validateFixedTable(listOf("astd_affix_not_exist")).isNotEmpty(),
            "未知 id 应报违规",
        )
        assertTrue(
            AffixRegistry.validateFixedTable(
                listOf(AffixRegistry.ID_ENGINE_OVERCLOCK, AffixRegistry.ID_ENGINE_OVERCLOCK),
            ).isNotEmpty(),
            "重复 id 应报违规",
        )
        for (pair in AffixRegistry.EXCLUSIVE_PAIRS) {
            assertTrue(
                AffixRegistry.validateFixedTable(pair.toList()).isNotEmpty(),
                "互斥对 $pair 共存应报违规",
            )
        }
        assertTrue(
            AffixRegistry.validateFixedTable(listOf(AffixRegistry.ID_PHASE_COIL_TUNING)).isNotEmpty(),
            "相位限定词缀进固定表应报违规",
        )
    }
}
