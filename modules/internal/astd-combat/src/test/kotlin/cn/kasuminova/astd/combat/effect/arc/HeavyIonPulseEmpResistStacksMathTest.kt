package cn.kasuminova.astd.combat.effect.arc

import cn.kasuminova.astd.api.buff.BuffHost
import cn.kasuminova.astd.combat.effect.arc.heavyionpulse.HeavyIonPulseEmpResistStacks
import cn.kasuminova.astd.combat.effect.arc.heavyionpulse.HeavyIonPulseTuning
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.MutableStat
import com.fs.starfarer.api.combat.ShipAPI
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import kotlin.test.Test
import kotlin.test.assertEquals

class HeavyIonPulseEmpResistStacksMathTest {

    private data class Fixture(
        val stacks: HeavyIonPulseEmpResistStacks,
        val empStat: MutableStat,
        val energyStat: MutableStat,
        val host: BuffHost,
    )

    private fun newFixture(baseEmpMult: Float = 1f, perStack: Float = 0.02f): Fixture {
        val empStat = MutableStat(baseEmpMult)
        val energyStat = MutableStat(1f)
        val stats = mock(MutableShipStatsAPI::class.java)
        `when`(stats.empDamageTakenMult).thenReturn(empStat)
        `when`(stats.energyDamageTakenMult).thenReturn(energyStat)

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
        return Fixture(stacks, empStat, energyStat, host)
    }

    @Test
    fun `绝对位移正算与逐帧幂等`() {
        val f = newFixture()
        f.stacks.addStacks(10)
        f.stacks.advance(0.01f)
        assertEquals(9, f.stacks.stacks)
        assertEquals(1.18f, f.empStat.modifiedValue, 1e-6f)

        f.stacks.advance(0.005f)
        assertEquals(1.18f, f.empStat.modifiedValue, 1e-6f)
    }

    @Test
    fun `他源抗性共存 绝对位移作用于他源终值`() {
        val f = newFixture()
        f.empStat.modifyMult("other_mod", 0.5f)
        f.stacks.addStacks(10)
        f.stacks.advance(0.01f)
        assertEquals(0.68f, f.empStat.modifiedValue, 1e-6f, "他源 0.5 + 0.18 位移 = 0.68")

        val g = newFixture(perStack = 0.05f)
        g.empStat.modifyMult("other_mod", 0.5f)
        g.stacks.addStacks(40)
        g.stacks.advance(0.001f)
        assertEquals(19, g.stacks.stacks)
        assertEquals(0.5f + 19 * 0.05f, g.empStat.modifiedValue, 1e-6f, "位移越界自然转易伤 1.45")
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
    fun `消散速率钉死 0_5 层每秒 10 层推进 2 秒降至 9 层`() {
        val f = newFixture()
        f.stacks.addStacks(10)
        f.stacks.advance(2f)
        assertEquals(9, f.stacks.stacks)
    }

    @Test
    fun `消散归零经 host 移除 onRemove 无残留`() {
        val f = newFixture()
        f.stacks.addStacks(3)
        f.stacks.advance(10f)
        assertEquals(0, f.stacks.stacks)
        verify(f.host).remove(f.stacks, null)

        val g = newFixture()
        g.stacks.addStacks(10)
        g.stacks.advance(0.01f)
        assertEquals(1.18f, g.empStat.modifiedValue, 1e-6f)
        g.stacks.onRemove()
        assertEquals(1.0f, g.empStat.modifiedValue, 1e-6f, "unmodify 后回他源终值无残留")
    }

    @Test
    fun `武备升级激活 超 100 削减转能量易伤 onRemove 双 stat 无残留`() {
        val f = newFixture(perStack = 0.08f)
        f.stacks.armamentUpgrade = true
        f.stacks.addStacks(20)
        f.stacks.advance(0.01f)
        assertEquals(19, f.stacks.stacks)
        assertEquals(2f, f.empStat.modifiedValue, 1e-6f, "EMP 位移封顶 100%：1.0 + 1.0 = 2.0")
        assertEquals(
            1f + (19 * 0.08f - 1f) / HeavyIonPulseTuning.ENERGY_VULN_DIVISOR,
            f.energyStat.modifiedValue,
            1e-6f,
            "超出部分按 5:1 转能量易伤",
        )

        f.stacks.advance(0.005f)
        assertEquals(2f, f.empStat.modifiedValue, 1e-6f)

        f.stacks.onRemove()
        assertEquals(1.0f, f.empStat.modifiedValue, 1e-6f)
        assertEquals(1.0f, f.energyStat.modifiedValue, 1e-6f, "能量易伤乘区无残留")
    }

    @Test
    fun `武备升级未激活 能量易伤恒不写入`() {
        val f = newFixture(perStack = 0.08f)
        f.stacks.addStacks(20)
        f.stacks.advance(0.01f)
        assertEquals(1f + 19 * 0.08f, f.empStat.modifiedValue, 1e-6f)
        assertEquals(1.0f, f.energyStat.modifiedValue, 1e-6f)
    }
}
