package cn.kasuminova.astd.combat.effect.arc.qiongjue

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.impl.buff.WarnCapture
import cn.kasuminova.astd.impl.buff.stubShip
import cn.kasuminova.astd.impl.buff.stubWeapon
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import com.fs.starfarer.api.combat.CombatEngineAPI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/**
 * 规格 05 §2.5 用例 1~9：穷距持续演算纯逻辑全量验证（全部调用真实逻辑，禁止源码 contain）。
 *
 * 覆盖：同目标叠层上限、异目标折算三档与归零边界、衰减窗口边界与清零即止、
 * 难度取值玩家固定 v2、乘区正算、目标失效不折算、decayRate=0 防线（WARN 恰好一次）。
 */
class QiongjueStackMathTest {

    /** 测试桩：固定 k_s 的 DifficultyTuning 接口实现（非反射），value 走 entry.map 真算。 */
    private class FakeTuning(override val fixedScale: Float) : DifficultyTuning {
        override fun value(entry: ScalingEntry): Float = entry.map.value(fixedScale, entry.v1, entry.v2, entry.v5)
    }

    @Test
    fun `用例1 同目标连续命中叠至上限`() {
        var stacks = 0
        val expected = listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 10, 10)
        expected.forEachIndexed { index, want ->
            stacks = QiongjueStackMath.stacksAfterHit(stacks, oldTargetValid = true, sameTarget = true, retainPct = 0.3125f)
            assertEquals(want, stacks, "第 ${index + 1} 次同目标命中层数")
        }
    }

    @Test
    fun `用例2 异目标折算三档`() {
        // stacks=10 切换：v1 → floor(10×0.25)+1=3；v2 → floor(3.125)+1=4；v5 → floor(5)+1=6。
        assertEquals(3, QiongjueStackMath.stacksAfterHit(10, oldTargetValid = true, sameTarget = false, retainPct = 0.25f))
        assertEquals(4, QiongjueStackMath.stacksAfterHit(10, oldTargetValid = true, sameTarget = false, retainPct = 0.3125f))
        assertEquals(6, QiongjueStackMath.stacksAfterHit(10, oldTargetValid = true, sameTarget = false, retainPct = 0.50f))
    }

    @Test
    fun `用例3 折算归零边界`() {
        // stacks=1，v1 retain=25% → floor(0.25)+1=1；明确合法，无 WARN（规格 05 §2.4）。
        assertEquals(1, QiongjueStackMath.stacksAfterHit(1, oldTargetValid = true, sameTarget = false, retainPct = 0.25f))
        // retainPct=0 极端自定义：floor(stacks×0)=0，无除零。
        assertEquals(1, QiongjueStackMath.stacksAfterHit(10, oldTargetValid = true, sameTarget = false, retainPct = 0f))
    }

    @Test
    fun `用例4 衰减窗口边界`() {
        // 恰 3.0s（含端）不衰减，累加器清零。
        val atWindow = QiongjueStackMath.decayAdvance(stacks = 10, pendingDecay = 0.5f, secondsSinceLastHit = 3.0f, amount = 0.016f, decayRate = 1.75f)
        assertEquals(10, atWindow.stacks)
        assertEquals(0f, atWindow.pendingDecay)

        // v2 1.75/s：窗口后 0.5714s（=1/1.75）扣 1 层。
        val v2 = QiongjueStackMath.decayAdvance(stacks = 10, pendingDecay = 0f, secondsSinceLastHit = 3.5714f, amount = 0.5714f, decayRate = 1.75f)
        assertEquals(9, v2.stacks)
        // v1 2/s：窗口后 0.5s 扣 1 层。
        val v1 = QiongjueStackMath.decayAdvance(stacks = 10, pendingDecay = 0f, secondsSinceLastHit = 3.5f, amount = 0.5f, decayRate = 2f)
        assertEquals(9, v1.stacks)
        // v5 1/s：窗口后 1s 扣 1 层。
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
        // 玩家 owner==0 恒 v2，与 k_s 无关。
        assertEquals(entry.v2, QiongjueStackMath.resolve(FakeTuning(1f), entry, owner = 0), 1e-6f)
        assertEquals(entry.v2, QiongjueStackMath.resolve(FakeTuning(5f), entry, owner = 0), 1e-6f)
        // 敌版三档走 tuning.value（FakeTuning 固定 k_s 断言三锚点）。
        assertEquals(entry.v1, QiongjueStackMath.resolve(FakeTuning(1f), entry, owner = 1), 1e-6f)
        assertEquals(entry.v2, QiongjueStackMath.resolve(FakeTuning(2f), entry, owner = 1), 1e-6f)
        assertEquals(entry.v5, QiongjueStackMath.resolve(FakeTuning(5f), entry, owner = 1), 1e-6f)
    }

    @Test
    fun `用例7 倍率正算`() {
        assertEquals(1.0f, QiongjueStackMath.mult(0, 0.0625f), 1e-6f)
        assertEquals(1.625f, QiongjueStackMath.mult(10, 0.0625f), 1e-6f)
        assertEquals(2.0f, QiongjueStackMath.mult(10, 0.10f), 1e-6f)
    }

    @Test
    fun `用例8 目标失效不折算`() {
        // 旧目标 hulk/离场 → 视为无旧目标：stacks+1 不走 retain（规格裁定：打死目标后转火不吃切换惩罚）。
        assertEquals(6, QiongjueStackMath.stacksAfterHit(5, oldTargetValid = false, sameTarget = false, retainPct = 0.25f))
        // 对照：旧目标有效且异目标 → 折算 floor(5×0.25)+1=2。
        assertEquals(2, QiongjueStackMath.stacksAfterHit(5, oldTargetValid = true, sameTarget = false, retainPct = 0.25f))
    }

    @Test
    fun `用例9 decayRate=0 防线`() {
        // 极端自定义 k_s：注入零值衰减速率锚点，驱动真实 Buff advance——不衰减且 WARN 恰好一次。
        val weapon = stubWeapon("WS 001", QiongjuePhaseRailgunDifficulty.WEAPON_ID)
        `when`(weapon.cooldownRemaining).thenReturn(0f)

        val ship = stubShip(hulk = false, weapons = listOf(weapon))
        `when`(ship.owner).thenReturn(1)
        `when`(ship.isAlive).thenReturn(true)

        val engine = mock(CombatEngineAPI::class.java)
        `when`(engine.getTotalElapsedTime(anyBoolean())).thenReturn(100f)
        `when`(engine.customData).thenReturn(HashMap())
        `when`(engine.playerShip).thenReturn(null)

        val buff = QiongjueCalcStacks(
            ship, weapon, engine,
            decayRateEntry = ScalingEntry(0f, 0f, 0f),
        )
        buff.addStacks(5)
        buff.lastHitTime = 0f // now-lastHit=100 > 3s 窗口，进入衰减分支

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
