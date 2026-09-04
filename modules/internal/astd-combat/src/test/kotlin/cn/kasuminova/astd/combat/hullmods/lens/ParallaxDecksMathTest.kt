package cn.kasuminova.astd.combat.hullmods.lens

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [ParallaxDecksMath] 纯判定测试（视差甲板，spec §4）。
 *
 * 覆盖三组边界：
 * - 出库相位隐形窗口（< windowSec）。
 * - 整备承伤减免范围（<= rangeSu）。
 * - 机群叠误差标记的每敌每秒上限冷却（now - last >= minInterval）。
 *
 * 这些判定是纯函数，故先写失败测试 → 实现 → 通过；hullmod 集成层（advanceInCombat
 * 枚举机群 / 设相位 / 配对 unapply）不在单测范围。
 */
class ParallaxDecksMathTest {

    // ---- 出库相位窗口 ----

    @Test
    fun `launch phase window is open right after deploy`() {
        assertTrue(ParallaxDecksMath.isInLaunchPhaseWindow(0f, ParallaxDecksMath.LAUNCH_PHASE_WINDOW))
        assertTrue(ParallaxDecksMath.isInLaunchPhaseWindow(1.99f, ParallaxDecksMath.LAUNCH_PHASE_WINDOW))
    }

    @Test
    fun `launch phase window is closed at and beyond the window edge`() {
        // 边界采用左闭右开：恰好到窗口时长即视为关闭（避免与 unapply 时刻重叠）。
        assertFalse(ParallaxDecksMath.isInLaunchPhaseWindow(2f, ParallaxDecksMath.LAUNCH_PHASE_WINDOW))
        assertFalse(ParallaxDecksMath.isInLaunchPhaseWindow(5f, ParallaxDecksMath.LAUNCH_PHASE_WINDOW))
    }

    @Test
    fun `launch phase window rejects negative elapsed`() {
        assertFalse(ParallaxDecksMath.isInLaunchPhaseWindow(-0.1f, ParallaxDecksMath.LAUNCH_PHASE_WINDOW))
    }

    // ---- 整备承伤减免范围 ----

    @Test
    fun `recovery range includes the exact edge`() {
        assertTrue(ParallaxDecksMath.isInRecoveryRange(0f, ParallaxDecksMath.RECOVERY_RANGE))
        assertTrue(ParallaxDecksMath.isInRecoveryRange(999.9f, ParallaxDecksMath.RECOVERY_RANGE))
        assertTrue(ParallaxDecksMath.isInRecoveryRange(1000f, ParallaxDecksMath.RECOVERY_RANGE))
    }

    @Test
    fun `recovery range excludes beyond edge`() {
        assertFalse(ParallaxDecksMath.isInRecoveryRange(1000.1f, ParallaxDecksMath.RECOVERY_RANGE))
        assertFalse(ParallaxDecksMath.isInRecoveryRange(2000f, ParallaxDecksMath.RECOVERY_RANGE))
    }

    // ---- 机群叠标记每敌每秒上限 ----

    @Test
    fun `mark can be applied when never applied before`() {
        // sentinel：lastAppliedTime < 0 表示该敌舰从未被本机群叠过标记。
        assertTrue(ParallaxDecksMath.canApplyMarkNow(-1f, 0f, ParallaxDecksMath.MARK_MIN_INTERVAL))
        assertTrue(ParallaxDecksMath.canApplyMarkNow(-1f, 123.4f, ParallaxDecksMath.MARK_MIN_INTERVAL))
    }

    @Test
    fun `mark is blocked within the min interval`() {
        // maxPerSec=1 → minInterval=1s。0.5s 后仍在冷却内。
        assertFalse(ParallaxDecksMath.canApplyMarkNow(10f, 10.5f, ParallaxDecksMath.MARK_MIN_INTERVAL))
        assertFalse(ParallaxDecksMath.canApplyMarkNow(10f, 10.99f, ParallaxDecksMath.MARK_MIN_INTERVAL))
    }

    @Test
    fun `mark unlocks at and after the min interval`() {
        assertTrue(ParallaxDecksMath.canApplyMarkNow(10f, 11f, ParallaxDecksMath.MARK_MIN_INTERVAL))
        assertTrue(ParallaxDecksMath.canApplyMarkNow(10f, 12.5f, ParallaxDecksMath.MARK_MIN_INTERVAL))
    }

    // ---- 常量自检 ----

    @Test
    fun `constants match spec anchors`() {
        assertEquals(2f, ParallaxDecksMath.LAUNCH_PHASE_WINDOW)
        assertEquals(1000f, ParallaxDecksMath.RECOVERY_RANGE)
        assertEquals(0.5f, ParallaxDecksMath.RECOVERY_DAMAGE_TAKEN_MULT)
        assertEquals(2.0f, ParallaxDecksMath.TIME_MULT_BONUS)
        // 每敌每秒上限取保守端 1 层/秒 → 间隔 1s。
        assertEquals(1f, ParallaxDecksMath.MARK_MIN_INTERVAL)
    }
}
