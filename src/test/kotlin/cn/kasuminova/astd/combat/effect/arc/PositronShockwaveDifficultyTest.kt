package cn.kasuminova.astd.combat.effect.arc

import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.impl.buff.WarnCapture
import com.fs.starfarer.api.combat.ShipAPI
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 规格 06 §4.1 用例 1~4：三锚点难度取值（玩家固定 v2 / 敌方迟暮 v1 / 敌方破晓 v5 /
 * 无主弹体按敌方口径并 WARN 恰好一次）。经 [DifficultyTuningImpl.installScaleForTests]
 * 走完整映射链路（对齐 ChargeNeedleTuningTest 先例）。
 */
class PositronShockwaveDifficultyTest {
    private var warnCapture: WarnCapture? = null

    @AfterTest
    fun tearDown() {
        DifficultyTuningImpl.installScaleForTests(null)
        warnCapture?.detach()
        warnCapture = null
    }

    private fun stubShip(owner: Int): ShipAPI {
        val ship = mock(ShipAPI::class.java)
        `when`(ship.owner).thenReturn(owner)
        return ship
    }

    @Test
    fun `用例1 玩家来源固定 v2，与难度档无关`() {
        DifficultyTuningImpl.installScaleForTests(5f)
        val v = PositronShockwaveDifficulty.resolve(stubShip(owner = 0))
        assertEquals(28.125f, v.halfAngleDeg, 1e-3f, "玩家锥半角应为 v2 56.25/2")
        assertEquals(250f, v.range, 1e-3f, "玩家锥长应为 v2 250")
        assertEquals(250f, v.damage, 1e-3f, "玩家伤害应为 200 × v2 1.25")
    }

    @Test
    fun `用例2 敌方迟暮档取 v1`() {
        DifficultyTuningImpl.installScaleForTests(1f)
        val v = PositronShockwaveDifficulty.resolve(stubShip(owner = 1))
        assertEquals(22.5f, v.halfAngleDeg, 1e-3f)
        assertEquals(200f, v.range, 1e-3f)
        assertEquals(200f, v.damage, 1e-3f)
    }

    @Test
    fun `用例3 敌方破晓档取 v5`() {
        DifficultyTuningImpl.installScaleForTests(5f)
        val v = PositronShockwaveDifficulty.resolve(stubShip(owner = 1))
        assertEquals(45f, v.halfAngleDeg, 1e-3f)
        assertEquals(400f, v.range, 1e-3f)
        assertEquals(400f, v.damage, 1e-3f)
    }

    @Test
    fun `用例4 无主弹体按敌方口径取值并 WARN 恰好一次`() {
        DifficultyTuningImpl.installScaleForTests(2f)
        val capture = WarnCapture(PositronShockwaveDifficulty::class.java)
        warnCapture = capture

        // 用例 1~3 与本用例在同类内顺序不保证：此处先消费掉可能的首次 WARN（once 守卫），再断言后续调用不再 WARN。
        PositronShockwaveDifficulty.resolve(null)
        val baseline = capture.events.size
        val v = PositronShockwaveDifficulty.resolve(null)
        PositronShockwaveDifficulty.resolve(null)

        assertEquals(28.125f, v.halfAngleDeg, 1e-3f, "无主弹体按敌方口径：k_s=2 应为 v2 等值")
        assertEquals(250f, v.range, 1e-3f)
        assertEquals(250f, v.damage, 1e-3f)
        assertEquals(1, baseline, "首次 resolve(null) 应输出恰好一条 WARN")
        assertEquals(baseline, capture.events.size, "后续 resolve(null) 不应重复 WARN（once 守卫）")
    }
}
