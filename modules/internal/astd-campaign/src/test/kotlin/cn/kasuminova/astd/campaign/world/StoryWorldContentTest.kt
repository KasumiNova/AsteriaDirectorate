package cn.kasuminova.astd.campaign.world

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 剧情状况数值缩放（k_s 三锚点端点命中）+ 引力节点状态机（拔除进度幂等）。
 */
class StoryWorldContentTest {

    /** 测试用固定 k_s 的轨一实现（不触碰 LunaLib/全局单例）。 */
    private class FixedTuning(override val fixedScale: Float) : DifficultyTuning {
        override fun value(entry: ScalingEntry): Float = entry.map.value(fixedScale, entry.v1, entry.v2, entry.v5)
    }

    @Test
    fun `菀星行政部遗址数值端点命中文档区间`() {
        val low = StoryConditionEffects.wanxingAdmin(FixedTuning(1f))
        assertEquals(0.10f, low.accessibility, 1e-4f)
        assertEquals(0.05f, low.income, 1e-4f)
        assertEquals(1f, low.stability, 1e-4f)
        assertEquals(0.25f, low.fleetSize, 1e-4f)

        val high = StoryConditionEffects.wanxingAdmin(FixedTuning(5f))
        assertEquals(0.50f, high.accessibility, 1e-4f)
        assertEquals(0.20f, high.income, 1e-4f)
        assertEquals(4f, high.stability, 1e-4f)
        assertEquals(1.00f, high.fleetSize, 1e-4f)
    }

    @Test
    fun `星坠工程部遗址数值端点命中文档区间`() {
        val low = StoryConditionEffects.starfallEngineering(FixedTuning(1f))
        assertEquals(0.05f, low.accessibility, 1e-4f)
        assertEquals(2f, low.heavyIndustryOutput, 1e-4f)
        assertEquals(0.50f, low.fleetSize, 1e-4f)
        assertEquals(2.00f, low.groundDefenses, 1e-4f)
        assertEquals(1f, low.maxIndustries, 1e-4f)

        val high = StoryConditionEffects.starfallEngineering(FixedTuning(5f))
        assertEquals(0.25f, high.accessibility, 1e-4f)
        assertEquals(6f, high.heavyIndustryOutput, 1e-4f)
        assertEquals(2.00f, high.fleetSize, 1e-4f)
        assertEquals(8.00f, high.groundDefenses, 1e-4f)
        assertEquals(3f, high.maxIndustries, 1e-4f)
    }

    @Test
    fun `视界动力数值端点命中文档区间`() {
        val low = StoryConditionEffects.eventHorizonPower(FixedTuning(1f))
        assertEquals(2f, low.maxIndustries, 1e-4f)
        assertEquals(0.15f, low.upkeepReduction, 1e-4f)
        assertEquals(0.10f, low.hazardReduction, 1e-4f)

        val high = StoryConditionEffects.eventHorizonPower(FixedTuning(5f))
        assertEquals(4f, high.maxIndustries, 1e-4f)
        assertEquals(0.75f, high.upkeepReduction, 1e-4f)
        assertEquals(0.50f, high.hazardReduction, 1e-4f)
    }

    @Test
    fun `紫菀科研部遗址数值端点命中文档区间`() {
        val low = StoryConditionEffects.asterResearch(FixedTuning(1f))
        assertEquals(0.10f, low.accessibility, 1e-4f)
        assertEquals(0.25f, low.fleetSize, 1e-4f)
        assertEquals(5f, low.immigrationWeight, 1e-4f)

        val high = StoryConditionEffects.asterResearch(FixedTuning(5f))
        assertEquals(0.50f, high.accessibility, 1e-4f)
        assertEquals(1.00f, high.fleetSize, 1e-4f)
        assertEquals(20f, high.immigrationWeight, 1e-4f)
    }

    @Test
    fun `设计基准 k_s=2 落在区间内`() {
        val base = FixedTuning(2f)
        val w = StoryConditionEffects.wanxingAdmin(base)
        assertTrue(w.accessibility in 0.10f..0.50f)
        val s = StoryConditionEffects.starfallEngineering(base)
        assertTrue(s.heavyIndustryOutput in 2f..6f)
        val p = StoryConditionEffects.eventHorizonPower(base)
        assertTrue(p.upkeepReduction in 0.15f..0.75f)
    }

    @Test
    fun `护卫舰队 FP 为基准 300 乘难度系数`() {
        assertEquals(300f, GravityNodes.guardFleetPoints(FixedTuning(1f)), 1e-4f)
        assertEquals(600f, GravityNodes.guardFleetPoints(FixedTuning(2f)), 1e-4f)
        assertEquals(1500f, GravityNodes.guardFleetPoints(FixedTuning(5f)), 1e-4f)
    }

    @Test
    fun `节点拔除进度状态机幂等`() {
        val state = StoryWorldState()
        assertEquals(0, state.pulledNodeCount())
        assertFalse(state.allNodesPulled())
        assertFalse(state.isNodePulled(StoryWorldIds.ASTER_NODE_1))

        // 重复拔除登记幂等
        state.gravityNodesPulled.add(StoryWorldIds.ASTER_NODE_1)
        state.gravityNodesPulled.add(StoryWorldIds.ASTER_NODE_1)
        assertEquals(1, state.pulledNodeCount())
        assertTrue(state.isNodePulled(StoryWorldIds.ASTER_NODE_1))
        assertFalse(state.allNodesPulled())

        // 非节点 id 不影响判定
        state.gravityNodesPulled.add("astd_story_aster_core_vault")
        assertFalse(state.allNodesPulled())

        state.gravityNodesPulled.add(StoryWorldIds.ASTER_NODE_2)
        state.gravityNodesPulled.add(StoryWorldIds.ASTER_NODE_3)
        assertTrue(state.allNodesPulled(), "三节点全部拔除后核心数据舱应解禁")
    }
}
