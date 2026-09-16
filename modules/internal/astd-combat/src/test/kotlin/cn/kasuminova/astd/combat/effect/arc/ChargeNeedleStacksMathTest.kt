package cn.kasuminova.astd.combat.effect.arc

import cn.kasuminova.astd.api.buff.BuffHost
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.FluxTrackerAPI
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.MutableStat
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipHullSpecAPI
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyFloat
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 规格 01 §4.1 用例 10~13（2026-09 机制修订）：浮点累加器 clamp（绝对上限 200 层）、
 * 连续消散（当前层数 3%/s、下限 2 层/s）、护盾维持乘区 + 固定软辐能逐帧直写与 200% 耗散折算、回收。
 * `MutableStat` 为具体类直接 `MutableStat(1f)` 真对象驱动，
 * ShipAPI/引擎等 jar 接口走 mockito（项目统一口径，禁止反射手搓代理）。
 */
class ChargeNeedleStacksMathTest {

    private data class Fixture(
        val stacks: ChargeNeedleStacks,
        val upkeepStat: MutableStat,
        val fluxTracker: FluxTrackerAPI,
        val host: BuffHost,
    )

    /** 默认目标：耗散 800 / 基础维持 400 / perStack 0.02 / 固定软辐能 3 su/s 每层（200% 上限 1600 不触发）。 */
    private fun newFixture(
        dissipation: Float = 800f,
        upkeep: Float = 400f,
        perStack: Float = 0.02f,
        flatPerStack: Float = 3f,
    ): Fixture {
        val upkeepStat = MutableStat(1f)
        val stats = mock(MutableShipStatsAPI::class.java)
        `when`(stats.shieldUpkeepMult).thenReturn(upkeepStat)
        `when`(stats.fluxDissipation).thenReturn(MutableStat(dissipation))

        val shieldSpec = mock(ShipHullSpecAPI.ShieldSpecAPI::class.java)
        `when`(shieldSpec.upkeepCost).thenReturn(upkeep)
        val hullSpec = mock(ShipHullSpecAPI::class.java)
        `when`(hullSpec.shieldSpec).thenReturn(shieldSpec)

        val fluxTracker = mock(FluxTrackerAPI::class.java)
        val ship = mock(ShipAPI::class.java)
        `when`(ship.mutableStats).thenReturn(stats)
        `when`(ship.hullSpec).thenReturn(hullSpec)
        `when`(ship.fluxTracker).thenReturn(fluxTracker)
        `when`(ship.isAlive).thenReturn(true)
        `when`(ship.isHulk).thenReturn(false)

        val engine = mock(CombatEngineAPI::class.java)
        `when`(engine.isEntityInPlay(ship)).thenReturn(true)
        `when`(engine.playerShip).thenReturn(null)

        val host = mock(BuffHost::class.java)
        val stacks = ChargeNeedleStacks(ship, engine, host)
        stacks.perStack = perStack
        stacks.flatPerStack = flatPerStack
        return Fixture(stacks, upkeepStat, fluxTracker, host)
    }

    @Test
    fun `用例10 叠层 clamp 返回实际增量`() {
        val f = newFixture()
        assertEquals(200, f.stacks.maxStacks, "层数上限为绝对上限 200（产出上限由耗散折算承担）")
        assertEquals(200, f.stacks.addStacks(260), "超上限叠加返回实际生效增量")
        assertEquals(200, f.stacks.stacks)
        assertEquals(0, f.stacks.addStacks(3), "满层再叠加增量为 0")
        assertEquals(-10, f.stacks.addStacks(-10), "负输入强制扣层返回实际扣减量")
        assertEquals(190, f.stacks.stacks)
    }

    @Test
    fun `用例11 连续消散 比例百分之三 与 下限两层每秒 亚层累计 不穿 0`() {
        // 9 层 → max(9×3%, 2) = 2 层/s：advance(0.1) 恰 -0.2 层。
        val f = newFixture()
        f.stacks.addStacks(9)
        f.stacks.advance(0.1f)
        assertEquals(8, f.stacks.stacks)

        // 重新从 9 起：decay(0.05f)×3 累计 -0.3，层数视图 floor 序列 [8, 8, 8]
        val g = newFixture()
        g.stacks.addStacks(9)
        val views = List(3) {
            g.stacks.advance(0.05f)
            g.stacks.stacks
        }
        assertEquals(listOf(8, 8, 8), views)

        // 比例路径：100 层 → max(100×3%, 2) = 3 层/s，advance(0.5) 恰 -1.5 层。
        val h = newFixture()
        h.stacks.addStacks(100)
        h.stacks.advance(0.5f)
        assertEquals(98, h.stacks.stacks)

        // 衰减不穿 0：大步长扣减后归零并经 host 移除
        g.stacks.advance(10f)
        assertEquals(0, g.stacks.stacks)
        verify(g.host).remove(g.stacks, null)
    }

    @Test
    fun `用例12 维持乘区与固定软辐能逐帧直写 回收无残留`() {
        val f = newFixture()
        f.stacks.addStacks(10)
        f.stacks.advance(0.01f)
        // 消散 2 层/s × 0.01s = 0.02 → 9.98，floor = 9。
        assertEquals(9, f.stacks.stacks)
        // 维持额外 400×9×0.02=72，固定软辐能 27 su/s，总和 99 ≤ 1600 → 折算 1。
        assertEquals(1f + 9 * 0.02f, f.upkeepStat.modifiedValue, 1e-6f, "9 层 × 2% → 乘区 1.18")
        verify(f.fluxTracker).increaseFlux(27f * 1f * 0.01f, false)

        f.stacks.onRemove()
        assertEquals(1.0f, f.upkeepStat.modifiedValue, 1e-6f, "unmodify 后回 1.0 无残留")
    }

    @Test
    fun `用例13 超上限两项按比例同步压缩 耗散为零整体压没`() {
        // 耗散 10 → 上限 20；总和 99 → 折算 20/99，乘区与软辐能同因子压缩。
        val f = newFixture(dissipation = 10f)
        f.stacks.addStacks(10)
        f.stacks.advance(0.01f)
        val factor = 20f / 99f
        assertEquals(1f + 9 * 0.02f * factor, f.upkeepStat.modifiedValue, 1e-6f)
        verify(f.fluxTracker).increaseFlux(27f * factor * 0.01f, false)

        // 耗散 ≤ 0 异常态：产出整体压没——乘区回 1、软辐能不写。
        val z = newFixture(dissipation = 0f)
        z.stacks.addStacks(10)
        z.stacks.advance(0.01f)
        assertEquals(1.0f, z.upkeepStat.modifiedValue, 1e-6f)
        verify(z.fluxTracker, never()).increaseFlux(anyFloat(), anyBoolean())
    }
}
