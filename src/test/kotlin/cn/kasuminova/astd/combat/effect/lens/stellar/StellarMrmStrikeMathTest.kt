package cn.kasuminova.astd.combat.effect.lens.stellar

import cn.kasuminova.astd.impl.buff.WarnCapture
import org.apache.log4j.Level
import org.lwjgl.util.vector.Vector2f
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 规格 08 §4.1 用例 1/2/4/5/6/11：撞线阈值三档、严格小于边界、战机增伤三档、
 * 武器 EMP 三档、爆炸倍率与固定半径、领先瞄准点——全部真实调用 [StellarMrmStrikeMath] 纯函数。
 */
class StellarMrmStrikeMathTest {
    private var warnCapture: WarnCapture? = null

    @AfterTest
    fun tearDown() {
        warnCapture?.detach()
        warnCapture = null
    }

    @Test
    fun `用例1 撞线阈值三档：弹体200乘h锚点得300、600、1500`() {
        val h = StellarMrmDifficulty.LINE_CROSS_H
        assertEquals(300f, StellarMrmStrikeMath.lineCrossThreshold(200f, h.v1), "v1 h=1.5 → 300")
        assertEquals(600f, StellarMrmStrikeMath.lineCrossThreshold(200f, h.v2), "v2 h=3.0 → 600")
        assertEquals(1500f, StellarMrmStrikeMath.lineCrossThreshold(200f, h.v5), "v5 h=7.5 → 1500")
    }

    @Test
    fun `用例2 撞线严格小于边界：599触发、600与601不触发`() {
        val threshold = StellarMrmStrikeMath.lineCrossThreshold(200f, StellarMrmDifficulty.LINE_CROSS_H.v2)
        assertEquals(600f, threshold)
        assertTrue(StellarMrmStrikeMath.shouldCross(599f, threshold), "hp=599 < 600 触发")
        assertFalse(StellarMrmStrikeMath.shouldCross(600f, threshold), "hp=600 == 600 不触发（严格小于）")
        assertFalse(StellarMrmStrikeMath.shouldCross(601f, threshold), "hp=601 > 600 不触发")
    }

    @Test
    fun `用例4 战机增伤三档：面板100乘倍率得50、100、250`() {
        val entry = StellarMrmDifficulty.FIGHTER_BONUS
        assertEquals(50f, StellarMrmStrikeMath.fighterBonusDamage(100f, entry.v1), "v1 ×0.5")
        assertEquals(100f, StellarMrmStrikeMath.fighterBonusDamage(100f, entry.v2), "v2 ×1.0")
        assertEquals(250f, StellarMrmStrikeMath.fighterBonusDamage(100f, entry.v5), "v5 ×2.5")
    }

    @Test
    fun `用例5 武器EMP三档：面板100乘倍率得200、400、1000`() {
        val entry = StellarMrmDifficulty.WEAPON_EMP
        assertEquals(200f, StellarMrmStrikeMath.weaponEmpDamage(100f, entry.v1), "v1 ×2")
        assertEquals(400f, StellarMrmStrikeMath.weaponEmpDamage(100f, entry.v2), "v2 ×4")
        assertEquals(1000f, StellarMrmStrikeMath.weaponEmpDamage(100f, entry.v5), "v5 ×10")
    }

    @Test
    fun `用例6 爆炸倍率与固定半径：面板100乘v2得100、半径常量50`() {
        assertEquals(
            100f,
            StellarMrmStrikeMath.explosionDamage(100f, StellarMrmDifficulty.EXPLOSION_MULT.v2),
            "v2 ×1.0 → 100",
        )
        assertEquals(50f, StellarMrmDifficulty.EXPLOSION_RADIUS, "爆炸范围固定 50su（常量守护，不缩放）")
    }

    @Test
    fun `用例11 领先瞄准点：速度外推与maxSpeed零值退回直瞄`() {
        val lead = StellarMrmStrikeMath.leadPoint(
            targetLoc = Vector2f(100f, 0f),
            targetVel = Vector2f(50f, 25f),
            dist = 200f,
            missileMaxSpeed = 100f,
        )
        assertEquals(200f, lead.x, 1e-4f, "t=2s 外推 x=100+50×2")
        assertEquals(50f, lead.y, 1e-4f, "t=2s 外推 y=0+25×2")

        val capture = WarnCapture(StellarMrmStrikeMath::class.java)
        warnCapture = capture
        val fallback1 = StellarMrmStrikeMath.leadPoint(Vector2f(100f, 0f), Vector2f(50f, 0f), 200f, 0f)
        val fallback2 = StellarMrmStrikeMath.leadPoint(Vector2f(100f, 0f), Vector2f(50f, 0f), 200f, 0f)
        assertEquals(100f, fallback1.x, 1e-4f, "maxSpeed=0 退回当前位置直瞄")
        assertEquals(0f, fallback1.y, 1e-4f)
        assertEquals(100f, fallback2.x, 1e-4f, "第二次同样退回直瞄")
        val warns = capture.events.filter { it.level == Level.WARN }
        assertEquals(1, warns.size, "maxSpeed=0 应输出恰好一条 WARN（一次性去重）")
    }
}
