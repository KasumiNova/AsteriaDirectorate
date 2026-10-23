package cn.kasuminova.astd.combat.effect.arc

import cn.kasuminova.astd.combat.effect.arc.positronshockwave.PositronShockwaveAutofireAI
import kotlin.math.atan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PositronShockwaveAutofireAITest {

    @Test
    fun `容差随距离单调收窄，中距按碰撞半径加余量折算半角`() {
        val radius = 40f
        val near = PositronShockwaveAutofireAI.hitToleranceDeg(radius, 200f)
        val mid = PositronShockwaveAutofireAI.hitToleranceDeg(radius, 500f)
        val far = PositronShockwaveAutofireAI.hitToleranceDeg(radius, 1000f)

        val expectedMid = Math.toDegrees(
            atan(((radius + PositronShockwaveAutofireAI.HIT_MARGIN_SU) / 500f).toDouble()),
        ).toFloat()
        assertEquals(expectedMid, mid, 1e-3f, "中距容差应为 atan((半径+余量)/距离)")
        assertTrue(near > mid && mid > far, "容差应随距离单调收窄：$near > $mid > $far")
    }

    @Test
    fun `零距离与超远距离分别夹紧在上下限`() {
        assertEquals(
            PositronShockwaveAutofireAI.MAX_HIT_TOLERANCE_DEG,
            PositronShockwaveAutofireAI.hitToleranceDeg(60f, 0f),
            "零距离应夹紧在上限，避免贴脸等不到开火窗口",
        )
        assertEquals(
            PositronShockwaveAutofireAI.MIN_HIT_TOLERANCE_DEG,
            PositronShockwaveAutofireAI.hitToleranceDeg(1f, 100000f),
            "超远距离小目标应夹紧在下限，避免放宽到明显脱靶",
        )
    }
}
