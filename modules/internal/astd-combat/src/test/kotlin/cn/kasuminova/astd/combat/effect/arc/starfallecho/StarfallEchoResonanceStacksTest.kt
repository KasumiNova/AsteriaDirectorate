package cn.kasuminova.astd.combat.effect.arc.starfallecho

import cn.kasuminova.astd.impl.buff.BuffHostImpl
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.MutableStat
import com.fs.starfarer.api.combat.ShipAPI
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import kotlin.test.Test
import kotlin.test.assertEquals

class StarfallEchoResonanceStacksTest {

    private var combatTime = 10f
    private val armorDamageTaken = MutableStat(1f)
    private val resonance = newResonance()

    @Test
    fun `普通命中最多叠加八层`() {
        repeat(8) { resonance.addStacks(1) }
        assertEquals(8, resonance.stacks)

        assertEquals(0, resonance.addStacks(1))
        assertEquals(8, resonance.stacks)
    }

    @Test
    fun `满层再命中重新等待两战斗秒才开始减层`() {
        repeat(8) { resonance.addStacks(1) }
        combatTime = 11f
        assertEquals(0, resonance.addStacks(1))

        combatTime = 12.75f
        resonance.advance(1.75f)
        assertEquals(8, resonance.stacks)

        combatTime = 13f
        resonance.advance(0.25f)
        assertEquals(7, resonance.stacks)
    }

    @Test
    fun `从最近一次普通命中起每满两战斗秒减一层并更新易伤`() {
        repeat(2) { resonance.addStacks(1) }
        combatTime = 11f
        resonance.addStacks(1)

        combatTime = 12.75f
        resonance.advance(1.75f)
        assertEquals(3, resonance.stacks)
        assertEquals(1f + 3 * resonance.perStack, armorDamageTaken.modifiedValue)

        combatTime = 13f
        resonance.advance(0.25f)
        assertEquals(2, resonance.stacks)
        assertEquals(1f + 2 * resonance.perStack, armorDamageTaken.modifiedValue)

        combatTime = 15f
        resonance.advance(2f)
        assertEquals(1, resonance.stacks)
        assertEquals(1f + resonance.perStack, armorDamageTaken.modifiedValue)

        combatTime = 17f
        resonance.advance(2f)
        assertEquals(0, resonance.stacks)
        assertEquals(1f, armorDamageTaken.modifiedValue)
    }

    @Test
    fun `减层时间到达时先扣一层再叠加新命中`() {
        repeat(8) { resonance.addStacks(1) }
        combatTime = 12f

        assertEquals(1, resonance.addStacks(1))
        assertEquals(8, resonance.stacks)
    }

    @Test
    fun `末发消耗返回有效层数并清空`() {
        repeat(3) { resonance.addStacks(1) }
        combatTime = 11.75f

        assertEquals(3, resonance.consume())
        assertEquals(0, resonance.stacks)
        assertEquals(0, resonance.consume())
    }

    @Test
    fun `减层时间到达时先扣一层再结算末发消耗`() {
        repeat(3) { resonance.addStacks(1) }
        combatTime = 12f

        assertEquals(2, resonance.consume())
        assertEquals(0, resonance.stacks)
    }

    @Test
    fun `跨过多个减层间隔时末发仅消耗剩余层数`() {
        repeat(5) { resonance.addStacks(1) }
        combatTime = 16.5f

        assertEquals(2, resonance.stacks)
        assertEquals(2, resonance.consume())
        assertEquals(0, resonance.stacks)
    }

    private fun newResonance(): StarfallEchoResonanceStacks {
        val stats = mock(MutableShipStatsAPI::class.java)
        `when`(stats.shieldDamageTakenMult).thenReturn(MutableStat(1f))
        `when`(stats.armorDamageTakenMult).thenReturn(armorDamageTaken)
        `when`(stats.hullDamageTakenMult).thenReturn(MutableStat(1f))
        `when`(stats.empDamageTakenMult).thenReturn(MutableStat(1f))

        val ship = mock(ShipAPI::class.java)
        `when`(ship.mutableStats).thenReturn(stats)
        val engine = mock(CombatEngineAPI::class.java)
        `when`(engine.getTotalElapsedTime(false)).thenAnswer { combatTime }
        val host = BuffHostImpl(mutableMapOf())
        return StarfallEchoResonanceStacks(ship, engine, host).also { host.register(it) }
    }
}
