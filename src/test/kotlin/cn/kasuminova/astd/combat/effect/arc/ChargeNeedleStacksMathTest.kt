package cn.kasuminova.astd.combat.effect.arc

import cn.kasuminova.astd.api.buff.BuffHost
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.MutableStat
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipHullSpecAPI
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 规格 01 §4.1 用例 10~12：浮点累加器 clamp、CONTINUOUS 衰减（10 层/s）、
 * 护盾维持乘区幂等刷新与回收。`MutableStat` 为具体类直接 `MutableStat(1f)` 真对象驱动，
 * ShipAPI/引擎等 jar 接口走 mockito（项目统一口径，禁止反射手搓代理）。
 */
class ChargeNeedleStacksMathTest {

    private data class Fixture(
        val stacks: ChargeNeedleStacks,
        val upkeepStat: MutableStat,
        val host: BuffHost,
    )

    /** 默认目标：耗散 800 / 基础维持 400 / perStack 0.02 → 安全闸 cap = 50。 */
    private fun newFixture(dissipation: Float = 800f, upkeep: Float = 400f, perStack: Float = 0.02f): Fixture {
        val upkeepStat = MutableStat(1f)
        val stats = mock(MutableShipStatsAPI::class.java)
        `when`(stats.shieldUpkeepMult).thenReturn(upkeepStat)
        `when`(stats.fluxDissipation).thenReturn(MutableStat(dissipation))

        val shieldSpec = mock(ShipHullSpecAPI.ShieldSpecAPI::class.java)
        `when`(shieldSpec.upkeepCost).thenReturn(upkeep)
        val hullSpec = mock(ShipHullSpecAPI::class.java)
        `when`(hullSpec.shieldSpec).thenReturn(shieldSpec)

        val ship = mock(ShipAPI::class.java)
        `when`(ship.mutableStats).thenReturn(stats)
        `when`(ship.hullSpec).thenReturn(hullSpec)
        `when`(ship.isAlive).thenReturn(true)
        `when`(ship.isHulk).thenReturn(false)

        val engine = mock(CombatEngineAPI::class.java)
        `when`(engine.isEntityInPlay(ship)).thenReturn(true)
        `when`(engine.playerShip).thenReturn(null)

        val host = mock(BuffHost::class.java)
        val stacks = ChargeNeedleStacks(ship, engine, host)
        stacks.perStack = perStack
        return Fixture(stacks, upkeepStat, host)
    }

    @Test
    fun `用例10 叠层 clamp 返回实际增量`() {
        val f = newFixture()
        assertEquals(50, f.stacks.maxStacks, "安全闸 cap 应为 50")
        assertEquals(50, f.stacks.addStacks(60), "超闸叠加返回实际生效增量")
        assertEquals(50, f.stacks.stacks)
        assertEquals(0, f.stacks.addStacks(3), "满层再叠加增量为 0")
        assertEquals(-10, f.stacks.addStacks(-10), "负输入强制扣层返回实际扣减量")
        assertEquals(40, f.stacks.stacks)
    }

    @Test
    fun `用例11 CONTINUOUS 衰减 10 层每秒 亚层累计 不穿 0`() {
        val f = newFixture()
        f.stacks.addStacks(9)

        // decay(0.1f) 恰 -1 层
        f.stacks.advance(0.1f)
        assertEquals(8, f.stacks.stacks)

        // 重新从 9 起：decay(0.05f)×3 累计 -1.5，层数视图 floor 序列 [8, 8, 7]
        val g = newFixture()
        g.stacks.addStacks(9)
        val views = List(3) {
            g.stacks.advance(0.05f)
            g.stacks.stacks
        }
        assertEquals(listOf(8, 8, 7), views)

        // 衰减不穿 0：大步长扣减后归零并经 host 移除
        g.stacks.advance(10f)
        assertEquals(0, g.stacks.stacks)
        verify(g.host).remove(g.stacks, null)
    }

    @Test
    fun `用例12 维持倍率刷新幂等与回收`() {
        val stat = MutableStat(1f)
        ChargeNeedleStacks.refreshUpkeep(stat, 50, 0.02f)
        assertEquals(2.0f, stat.modifiedValue, 1e-6f, "50 层 × 2% → 乘区 2.0")

        // 同一 modifierId 二次幂等刷新不叠乘
        ChargeNeedleStacks.refreshUpkeep(stat, 50, 0.02f)
        assertEquals(2.0f, stat.modifiedValue, 1e-6f)

        ChargeNeedleStacks.clearUpkeep(stat)
        assertEquals(1.0f, stat.modifiedValue, 1e-6f, "unmodify 后回 1.0 无残留")
    }
}
