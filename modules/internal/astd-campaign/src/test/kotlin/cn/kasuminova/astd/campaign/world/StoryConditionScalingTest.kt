package cn.kasuminova.astd.campaign.world

import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.AfterTest

/**
 * 四个剧情市场状况的难度缩放校验：对照 docs/story/03、07 的数值区间，
 * 验证三锚点在 k_s=1/5 命中文档上下限、区间内单调、整数取整后仍落在文档区间。
 */
internal class StoryConditionScalingTest {

    @AfterTest
    fun clearScale() {
        DifficultyTuningImpl.installScaleForTests(null)
    }

    private fun valueAt(entry: ScalingEntry, ks: Float): Float {
        DifficultyTuningImpl.installScaleForTests(ks)
        return DifficultyTuningImpl.value(entry)
    }

    private fun assertEntry(
        entry: ScalingEntry,
        min: Float,
        base: Float,
        max: Float,
        label: String,
    ) {
        assertEquals(min, valueAt(entry, 1f), "$label k=1 应命中文档下限")
        assertEquals(base, valueAt(entry, 2f), "$label k=2 应命中设计基准")
        assertEquals(max, valueAt(entry, 5f), "$label k=5 应命中文档上限")
        assertTrue(valueAt(entry, 3f) in base..max, "$label k=3 应落在基准与上限之间")
    }

    @Test
    fun `admin ruins entries match chapter zero ranges`() {
        assertEntry(StoryConditionAdminRuins.ACCESS, 0.10f, 0.20f, 0.50f, "流通性")
        assertEntry(StoryConditionAdminRuins.INCOME, 0.05f, 0.10f, 0.20f, "收入系数")
        assertEntry(StoryConditionAdminRuins.STABILITY, 1f, 2f, 4f, "稳定性")
        assertEntry(StoryConditionAdminRuins.FLEET_SIZE, 0.25f, 0.50f, 1.00f, "舰队规模")

        // 稳定性整数取整：1~4
        val rounded = listOf(1f, 2f, 3f, 5f).map { valueAt(StoryConditionAdminRuins.STABILITY, it).roundToInt() }
        assertTrue(rounded.all { it in 1..4 }, "稳定性取整应落在 +1~+4: $rounded")
    }

    @Test
    fun `starfall engineering ruins entries match chapter two ranges`() {
        assertEntry(StoryConditionStarfallEngRuins.ACCESS, 0.05f, 0.10f, 0.25f, "流通性")
        assertEntry(StoryConditionStarfallEngRuins.HEAVY_PRODUCTION, 2f, 3f, 6f, "重工业产量")
        assertEntry(StoryConditionStarfallEngRuins.FLEET_SIZE, 0.50f, 1.00f, 2.00f, "舰队规模")
        assertEntry(StoryConditionStarfallEngRuins.GROUND_DEFENSE, 2.00f, 4.00f, 8.00f, "地面防御")
        assertEntry(StoryConditionStarfallEngRuins.MAX_INDUSTRIES, 1f, 2f, 3f, "最大工业设施数量")

        // 整数语义项取整后仍落在文档区间
        val prod = listOf(1f, 2f, 3f, 5f).map { valueAt(StoryConditionStarfallEngRuins.HEAVY_PRODUCTION, it).roundToInt() }
        assertTrue(prod.all { it in 2..6 }, "重工业产量取整应落在 +2~+6: $prod")
        val maxInd = listOf(1f, 2f, 3f, 5f).map { valueAt(StoryConditionStarfallEngRuins.MAX_INDUSTRIES, it).roundToInt() }
        assertTrue(maxInd.all { it in 1..3 }, "最大工业设施数量取整应落在 +1~+3: $maxInd")
    }

    @Test
    fun `event horizon power entries match chapter two ranges`() {
        assertEntry(StoryConditionEventHorizonPower.MAX_INDUSTRIES, 2f, 3f, 4f, "最大工业设施数量")
        assertEntry(StoryConditionEventHorizonPower.UPKEEP_REDUCTION, 0.15f, 0.30f, 0.75f, "建筑维护费减免")
        assertEntry(StoryConditionEventHorizonPower.HAZARD_REDUCTION, 0.10f, 0.20f, 0.50f, "危险度减免")

        assertEquals(1500f, StoryConditionEventHorizonPower.AURA_RADIUS_SU, "光环半径应为文档口径 1500su")
        assertTrue(
            StoryConditionEventHorizonPower.MEM_PROTECTED.isNotBlank() &&
                !StoryConditionEventHorizonPower.MEM_PROTECTED.startsWith("$"),
            "免疫标记 memory 键必须随存档持久化（不带 $ 前缀）",
        )

        // 维护费/危险度乘区不得突破 0（即减免 <100%）
        val upkeepMult = 1f - valueAt(StoryConditionEventHorizonPower.UPKEEP_REDUCTION, 5f)
        val hazardMult = 1f - valueAt(StoryConditionEventHorizonPower.HAZARD_REDUCTION, 5f)
        assertTrue(upkeepMult > 0f && hazardMult > 0f, "维护费/危险度乘区必须为正")
    }

    @Test
    fun `aster research ruins entries match chapter two ranges`() {
        assertEntry(StoryConditionAsterResearchRuins.ACCESS, 0.10f, 0.20f, 0.50f, "流通性")
        assertEntry(StoryConditionAsterResearchRuins.FLEET_SIZE, 0.25f, 0.50f, 1.00f, "舰队规模")
        assertEntry(StoryConditionAsterResearchRuins.GROWTH, 5f, 10f, 20f, "人口增长")

        val growth = listOf(1f, 2f, 3f, 5f).map { valueAt(StoryConditionAsterResearchRuins.GROWTH, it).roundToInt() }
        assertTrue(growth.all { it in 5..20 }, "人口增长取整应落在 +5~+20: $growth")
    }

    @Test
    fun `tooltip formatting helpers produce signed values`() {
        assertEquals("+25%", StoryConditionBase.formatPercent(0.25f))
        assertEquals("-15%", StoryConditionBase.formatPercent(-0.15f))
        assertEquals("+2", StoryConditionBase.formatSignedInt(2))
        assertEquals("-1", StoryConditionBase.formatSignedInt(-1))
    }
}
