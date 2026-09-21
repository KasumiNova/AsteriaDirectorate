package cn.kasuminova.astd.combat.effect.arc

import cn.kasuminova.astd.api.buff.BuffHost
import cn.kasuminova.astd.combat.effect.arc.heavyionpulse.HeavyIonPulseEmpResistStacks
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.MutableStat
import com.fs.starfarer.api.combat.ShipAPI
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 彗星冲击波 EMP 抗性削减叠层（HeavyIonPulseEmpResistStacks）逻辑验证（全部调用真实逻辑）：
 * 绝对位移幂等刷新、他源 modifier 共存、完全免疫不写、逐帧不叠乘、消散与回收。
 * `MutableStat` 为具体类直接真对象驱动，ShipAPI/引擎等 jar 接口走 mockito（项目统一口径，禁止反射手搓代理）。
 */
class HeavyIonPulseEmpResistStacksMathTest {

    private data class Fixture(
        val stacks: HeavyIonPulseEmpResistStacks,
        val empStat: MutableStat,
        val host: BuffHost,
    )

    private fun newFixture(baseEmpMult: Float = 1f, perStack: Float = 0.02f): Fixture {
        val empStat = MutableStat(baseEmpMult)
        val stats = mock(MutableShipStatsAPI::class.java)
        `when`(stats.empDamageTakenMult).thenReturn(empStat)

        val ship = mock(ShipAPI::class.java)
        `when`(ship.mutableStats).thenReturn(stats)
        `when`(ship.isAlive).thenReturn(true)
        `when`(ship.isHulk).thenReturn(false)

        val engine = mock(CombatEngineAPI::class.java)
        `when`(engine.isEntityInPlay(ship)).thenReturn(true)
        `when`(engine.playerShip).thenReturn(null)

        val host = mock(BuffHost::class.java)
        val stacks = HeavyIonPulseEmpResistStacks(ship, engine, host)
        stacks.perStack = perStack
        return Fixture(stacks, empStat, host)
    }

    @Test
    fun `绝对位移正算与逐帧幂等`() {
        val f = newFixture()
        f.stacks.addStacks(10)
        f.stacks.advance(0.01f)
        // 消散 1 层/s × 0.01s → 9.99，floor = 9；位移 1.0 + 9×0.02 = 1.18。
        assertEquals(9, f.stacks.stacks)
        assertEquals(1.18f, f.empStat.modifiedValue, 1e-6f)

        // 同层数二次刷新：unmodify→读回 1.0→重写 1.18，不叠乘。
        f.stacks.advance(0.005f)
        assertEquals(1.18f, f.empStat.modifiedValue, 1e-6f)
    }

    @Test
    fun `他源抗性共存 绝对位移作用于他源终值`() {
        // 目标自带 50% EMP 抗性（他源 ×0.5）：位移 0.5 + 9×0.02 = 0.68（而非 1.18）。
        val f = newFixture()
        f.empStat.modifyMult("other_mod", 0.5f)
        f.stacks.addStacks(10)
        f.stacks.advance(0.01f)
        assertEquals(0.68f, f.empStat.modifiedValue, 1e-6f, "他源 0.5 + 0.18 位移 = 0.68")

        // 易伤转化（隐藏机制）：位移越过他源终值 → mult 破 1（0.5 + 40×0.05 = 2.5）。
        val g = newFixture(perStack = 0.05f)
        g.empStat.modifyMult("other_mod", 0.5f)
        g.stacks.addStacks(40)
        g.stacks.advance(0.001f)
        assertEquals(39, g.stacks.stacks)
        assertEquals(0.5f + 39 * 0.05f, g.empStat.modifiedValue, 1e-6f, "位移越界自然转易伤 2.45")
    }

    @Test
    fun `完全免疫不写 stat 层数照常消散`() {
        val f = newFixture(baseEmpMult = 0f)
        f.stacks.addStacks(5)
        f.stacks.advance(0.01f)
        assertEquals(4, f.stacks.stacks)
        assertEquals(0f, f.empStat.modifiedValue, 1e-6f, "0 乘区不可突破，不写入")
    }

    @Test
    fun `消散归零经 host 移除 onRemove 无残留`() {
        val f = newFixture()
        f.stacks.addStacks(3)
        f.stacks.advance(10f)
        assertEquals(0, f.stacks.stacks)
        verify(f.host).remove(f.stacks, null)

        // onRemove 恰一次 unmodify：先造出有写入的状态再回收。
        val g = newFixture()
        g.stacks.addStacks(10)
        g.stacks.advance(0.01f)
        assertEquals(1.18f, g.empStat.modifiedValue, 1e-6f)
        g.stacks.onRemove()
        assertEquals(1.0f, g.empStat.modifiedValue, 1e-6f, "unmodify 后回他源终值无残留")
    }
}
