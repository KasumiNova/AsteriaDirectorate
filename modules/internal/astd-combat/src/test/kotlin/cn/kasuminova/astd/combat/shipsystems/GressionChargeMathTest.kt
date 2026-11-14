package cn.kasuminova.astd.combat.shipsystems

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GressionChargeMathTest {
    @Test
    fun `扇面全角折半且拒绝负值`() {
        assertEquals(30f, GressionChargeMath.halfAngleDeg(60f))
        assertEquals(0f, GressionChargeMath.halfAngleDeg(-10f))
    }

    @Test
    fun `质量倍率按加成比例计算`() {
        assertEquals(3f, GressionChargeMath.massMultiplier(2f))
        assertEquals(1f, GressionChargeMath.massMultiplier(-1f))
    }

    @Test
    fun `剧烈碰撞只接受无弹体来源的瞬时碰撞伤害`() {
        assertTrue(GressionChargeMath.isHeavyCollision(true, true, false, 80f, 60f))
        assertFalse(GressionChargeMath.isHeavyCollision(true, true, false, 20f, 60f))
        assertFalse(GressionChargeMath.isHeavyCollision(false, true, false, 800f, 60f))
        assertFalse(GressionChargeMath.isHeavyCollision(true, false, false, 800f, 60f))
        assertFalse(GressionChargeMath.isHeavyCollision(true, true, true, 800f, 60f))
        assertFalse(GressionChargeMath.isHeavyCollision(true, true, false, Float.NaN, 60f))
    }

    @Test
    fun `碰撞与正常结束合计只结算一次`() {
        val sprint = GressionChargeMath.beginSprint(GressionChargeMath.Sprint())
        assertFalse(GressionChargeMath.onCollision(sprint, false).settled)
        val hit = GressionChargeMath.onCollision(sprint, true)
        assertTrue(hit.settled)
        assertEquals(hit, GressionChargeMath.onCollision(hit, true))
        val ended = GressionChargeMath.onSprintEnd(hit)
        assertTrue(ended.settled)
        assertEquals(GressionChargeMath.Phase.DONE, ended.phase)
        assertEquals(ended, GressionChargeMath.onSprintEnd(ended))
        assertEquals(ended, GressionChargeMath.beginSprint(ended))
    }

    @Test
    fun `蓄能阶段碰撞不触发冲击且正常冲刺结束会触发`() {
        val charging = GressionChargeMath.Sprint(GressionChargeMath.Phase.CHARGING)
        assertEquals(charging, GressionChargeMath.onCollision(charging, true))
        assertEquals(charging, GressionChargeMath.onSprintEnd(charging))
        val ended = GressionChargeMath.onSprintEnd(GressionChargeMath.beginSprint(charging))
        assertTrue(ended.settled)
    }

    @Test
    fun `迎面目标被推向外侧且同向目标继续加速`() {
        val approaching = -900f
        val receding = 300f
        assertEquals(200f, approaching + GressionChargeMath.pushImpulse(approaching, 200f))
        assertEquals(500f, receding + GressionChargeMath.pushImpulse(receding, 200f))
        assertEquals(200f, GressionChargeMath.pushImpulse(0f, 200f))
    }

    @Test
    fun `推速按质量比缩放且轻重目标有界`() {
        val equal = GressionChargeMath.pushSpeed(2000f, 2000f)
        val lighter = GressionChargeMath.pushSpeed(2000f, 1000f)
        val heavier = GressionChargeMath.pushSpeed(2000f, 4000f)
        assertTrue(lighter > equal)
        assertTrue(heavier < equal)
        assertEquals(equal, GressionChargeMath.pushSpeed(4000f, 4000f), 0.001f)
        assertEquals(GressionChargeMath.pushSpeed(2000f, 0f), GressionChargeMath.pushSpeed(2000f, 0.1f))
        assertTrue(GressionChargeMath.pushSpeed(2000f, 100000f) > 0f)
        assertEquals(0f, GressionChargeMath.pushSpeed(0f, 100f))
    }
}
