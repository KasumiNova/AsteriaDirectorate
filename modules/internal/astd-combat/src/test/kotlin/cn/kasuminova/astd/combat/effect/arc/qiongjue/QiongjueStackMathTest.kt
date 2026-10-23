package cn.kasuminova.astd.combat.effect.arc.qiongjue

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.impl.buff.WarnCapture
import cn.kasuminova.astd.impl.buff.stubShip
import cn.kasuminova.astd.impl.buff.stubWeapon
import com.fs.starfarer.api.combat.CombatEngineAPI
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QiongjueStackMathTest {

    private class FakeTuning(override val fixedScale: Float) : DifficultyTuning {
        override fun value(entry: ScalingEntry): Float = entry.map.value(fixedScale, entry.v1, entry.v2, entry.v5)
    }

    @Test
    fun `用例1 同目标连续命中叠至上限`() {
        var stacks = 0
        val expected = listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 10, 10)
        expected.forEachIndexed { index, want ->
            stacks = QiongjueStackMath.stacksAfterHit(stacks, oldTargetValid = true, sameTarget = true, retainPct = 0.5f)
            assertEquals(want, stacks, "第 ${index + 1} 次同目标命中层数")
        }
    }

    @Test
    fun `用例2 异目标折算三档`() {
        assertEquals(3, QiongjueStackMath.stacksAfterHit(10, oldTargetValid = true, sameTarget = false, retainPct = 0.25f))
        assertEquals(6, QiongjueStackMath.stacksAfterHit(10, oldTargetValid = true, sameTarget = false, retainPct = 0.50f))
        assertEquals(10, QiongjueStackMath.stacksAfterHit(10, oldTargetValid = true, sameTarget = false, retainPct = 1.0f))
    }

    @Test
    fun `用例3 折算归零边界`() {
        assertEquals(1, QiongjueStackMath.stacksAfterHit(1, oldTargetValid = true, sameTarget = false, retainPct = 0.25f))
        assertEquals(1, QiongjueStackMath.stacksAfterHit(10, oldTargetValid = true, sameTarget = false, retainPct = 0f))
    }

    @Test
    fun `用例4 衰减窗口边界`() {
        val atWindow =
            QiongjueStackMath.decayAdvance(stacks = 10, pendingDecay = 0.5f, secondsSinceLastHit = 3.0f, amount = 0.016f, decayRate = 1.75f)
        assertEquals(10, atWindow.stacks)
        assertEquals(0f, atWindow.pendingDecay)

        val v2 = QiongjueStackMath.decayAdvance(stacks = 10, pendingDecay = 0f, secondsSinceLastHit = 3.5714f, amount = 0.5714f, decayRate = 1.75f)
        assertEquals(9, v2.stacks)
        val v1 = QiongjueStackMath.decayAdvance(stacks = 10, pendingDecay = 0f, secondsSinceLastHit = 3.5f, amount = 0.5f, decayRate = 2f)
        assertEquals(9, v1.stacks)
        val v5 = QiongjueStackMath.decayAdvance(stacks = 10, pendingDecay = 0f, secondsSinceLastHit = 4.0f, amount = 1.0f, decayRate = 1f)
        assertEquals(9, v5.stacks)
    }

    @Test
    fun `用例5 衰减清零即止`() {
        var stacks = 2
        var pending = 0f
        var sinceHit = 3.1f
        repeat(10) {
            val step = QiongjueStackMath.decayAdvance(stacks, pending, sinceHit, amount = 1.0f, decayRate = 1.75f)
            stacks = step.stacks
            pending = step.pendingDecay
            sinceHit += 1.0f
        }
        assertEquals(0, stacks, "持续 advance 扣到 0 后不再扣")
        assertTrue(pending <= 1f + QiongjueStackMath.STACK_EPS, "pendingDecay 被 clamp 不溢出: $pending")
    }

    @Test
    fun `用例6 难度取值玩家固定 v2`() {
        val entry = QiongjuePhaseRailgunDifficulty.PER_STACK_BONUS
        assertEquals(entry.v2, QiongjueStackMath.resolve(FakeTuning(1f), entry, owner = 0), 1e-6f)
        assertEquals(entry.v2, QiongjueStackMath.resolve(FakeTuning(5f), entry, owner = 0), 1e-6f)
        assertEquals(entry.v1, QiongjueStackMath.resolve(FakeTuning(1f), entry, owner = 1), 1e-6f)
        assertEquals(entry.v2, QiongjueStackMath.resolve(FakeTuning(2f), entry, owner = 1), 1e-6f)
        assertEquals(entry.v5, QiongjueStackMath.resolve(FakeTuning(5f), entry, owner = 1), 1e-6f)
    }

    @Test
    fun `用例7 倍率正算`() {
        assertEquals(1.0f, QiongjueStackMath.mult(0, 0.05f), 1e-6f)
        assertEquals(1.5f, QiongjueStackMath.mult(10, 0.05f), 1e-6f)
        assertEquals(2.25f, QiongjueStackMath.mult(10, 0.125f), 1e-6f)
    }

    @Test
    fun `用例8 目标失效不折算`() {
        assertEquals(6, QiongjueStackMath.stacksAfterHit(5, oldTargetValid = false, sameTarget = false, retainPct = 0.25f))
        assertEquals(2, QiongjueStackMath.stacksAfterHit(5, oldTargetValid = true, sameTarget = false, retainPct = 0.25f))
    }

    @Test
    fun `用例9 decayRate=0 防线`() {
        val weapon = stubWeapon("WS 001", QiongjuePhaseRailgunDifficulty.WEAPON_ID)
        `when`(weapon.cooldownRemaining).thenReturn(0f)

        val ship = stubShip(hulk = false, weapons = listOf(weapon))
        `when`(ship.owner).thenReturn(1)
        `when`(ship.isAlive).thenReturn(true)

        val engine = mock(CombatEngineAPI::class.java)
        `when`(engine.getTotalElapsedTime(anyBoolean())).thenReturn(100f)
        `when`(engine.customData).thenReturn(HashMap())
        `when`(engine.playerShip).thenReturn(null)

        val host = mock(cn.kasuminova.astd.api.buff.BuffHost::class.java)
        val buff = QiongjueCalcStacks(
            ship, engine, host,
            decayRateEntry = ScalingEntry(0f, 0f, 0f),
        )
        buff.addStacks(5)
        buff.lastHitTime = 0f

        val capture = WarnCapture(QiongjueCalcStacks::class.java)
        try {
            buff.advance(0.016f)
            buff.advance(0.016f)
            assertEquals(5, buff.stacks, "decayRate=0 时层数不衰减")
            val warns = capture.messages().filter { it.contains("衰减速率非法") }
            assertEquals(1, warns.size, "WARN 恰好一次: $warns")
        } finally {
            capture.detach()
        }
    }
}
