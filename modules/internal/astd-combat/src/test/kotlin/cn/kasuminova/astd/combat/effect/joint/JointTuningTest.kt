package cn.kasuminova.astd.combat.effect.joint

import cn.kasuminova.astd.combat.effect.joint.stardust.StardustMoteIds
import cn.kasuminova.astd.combat.effect.joint.stardust.StardustMoteTuning
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import com.fs.starfarer.api.combat.ShipAPI
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 联制线（20-joint.md）三处难度数值声明的契约测试：
 * 落叶飞花 / 视界变速 / 星尘光尘的三锚点解析与体型分档、玩家固定 v2 口径。
 * 通过 [DifficultyTuningImpl.installScaleForTests] 注入系数走完整映射链路（先例 BountyScalingHullModTest）。
 */
class JointTuningTest {

    @AfterTest
    fun clearOverride() {
        DifficultyTuningImpl.installScaleForTests(null)
    }

    /** 注入难度系数并返回单例读取面（测后经 [clearOverride] 复位）。 */
    private fun tuning(scale: Float): DifficultyTuningImpl {
        DifficultyTuningImpl.installScaleForTests(scale)
        return DifficultyTuningImpl
    }

    // —— 落叶飞花（v1 +200%/+100%/+100%，v2 +250%/+150%/+200%，v5 +400%/+250%/+500%）——

    @Test
    fun `落叶飞花 玩家固定 v2 砺刃档`() {
        val v = BurstFlowTuning.resolve(tuning(5f), isPlayer = true)
        assertEquals(3.5f, v.timeMult, 1e-6f)
        assertEquals(2.5f, v.speedManeuverMult, 1e-6f)
        assertEquals(3.0f, v.ammoRegenMult, 1e-6f)
    }

    @Test
    fun `落叶飞花 敌方按 k_s 映射三锚点`() {
        val v1 = BurstFlowTuning.resolve(tuning(1f), isPlayer = false)
        assertEquals(3.0f, v1.timeMult, 1e-6f)
        assertEquals(2.0f, v1.speedManeuverMult, 1e-6f)
        assertEquals(2.0f, v1.ammoRegenMult, 1e-6f)

        val v5 = BurstFlowTuning.resolve(tuning(5f), isPlayer = false)
        assertEquals(5.0f, v5.timeMult, 1e-6f)
        assertEquals(3.5f, v5.speedManeuverMult, 1e-6f)
        assertEquals(6.0f, v5.ammoRegenMult, 1e-6f)
    }

    // —— 视界变速（自身 +50%~100%；目标压制按体型四档；承伤修正双向）——

    @Test
    fun `视界变速 体型分档四档映射`() {
        assertSame(VisionShiftTuning.TARGET_TIME_REDUCTION_FRIGATE, VisionShiftTuning.reductionForSize(ShipAPI.HullSize.FRIGATE))
        assertSame(VisionShiftTuning.TARGET_TIME_REDUCTION_DESTROYER, VisionShiftTuning.reductionForSize(ShipAPI.HullSize.DESTROYER))
        assertSame(VisionShiftTuning.TARGET_TIME_REDUCTION_CRUISER, VisionShiftTuning.reductionForSize(ShipAPI.HullSize.CRUISER))
        assertSame(VisionShiftTuning.TARGET_TIME_REDUCTION_CAPITAL, VisionShiftTuning.reductionForSize(ShipAPI.HullSize.CAPITAL_SHIP))
        // 战机/DEFAULT/null 按护卫舰档兜底
        assertSame(VisionShiftTuning.TARGET_TIME_REDUCTION_FRIGATE, VisionShiftTuning.reductionForSize(ShipAPI.HullSize.FIGHTER))
        assertSame(VisionShiftTuning.TARGET_TIME_REDUCTION_FRIGATE, VisionShiftTuning.reductionForSize(null))
    }

    @Test
    fun `视界变速 玩家固定 v2 且数值为最终乘区口径`() {
        val v = VisionShiftTuning.resolve(tuning(5f), isPlayer = true, ShipAPI.HullSize.CRUISER)
        assertEquals(1.75f, v.selfTimeMult, 1e-6f)
        assertEquals(0.55f, v.targetTimeMult, 1e-6f)
        assertEquals(1.625f, v.damageFromSelfMult, 1e-6f)
        assertEquals(0.4f, v.damageFromOthersMult, 1e-6f)
    }

    @Test
    fun `视界变速 敌方 v1 下限克制`() {
        val v = VisionShiftTuning.resolve(tuning(1f), isPlayer = false, ShipAPI.HullSize.FRIGATE)
        assertEquals(1.5f, v.selfTimeMult, 1e-6f)
        assertEquals(0.6f, v.targetTimeMult, 1e-6f)
        assertEquals(1.25f, v.damageFromSelfMult, 1e-6f)
        assertEquals(0.2f, v.damageFromOthersMult, 1e-6f)
    }

    // —— 星尘光尘（导弹 +50%~250% / 战机 +100%~500% / EMP 100%~500%）——

    @Test
    fun `星尘光尘 玩家固定 v2 与敌方锚点`() {
        val player = StardustMoteTuning.resolve(tuning(5f), isPlayer = true)
        assertEquals(1.5f, player.antiMissileBonus, 1e-6f)
        assertEquals(3.0f, player.antiFighterBonus, 1e-6f)
        assertEquals(3.0f, player.shipEmpMult, 1e-6f)

        val v1 = StardustMoteTuning.resolve(tuning(1f), isPlayer = false)
        assertEquals(0.5f, v1.antiMissileBonus, 1e-6f)
        assertEquals(1.0f, v1.antiFighterBonus, 1e-6f)
        assertEquals(1.0f, v1.shipEmpMult, 1e-6f)

        val v5 = StardustMoteTuning.resolve(tuning(5f), isPlayer = false)
        assertEquals(2.5f, v5.antiMissileBonus, 1e-6f)
        assertEquals(5.0f, v5.antiFighterBonus, 1e-6f)
        assertEquals(5.0f, v5.shipEmpMult, 1e-6f)
    }

    @Test
    fun `星尘光尘 双线配色与 id 判定`() {
        assertSame(StardustMoteTuning.ARC_COLOR, StardustMoteTuning.colorForProj(StardustMoteIds.PROJ_ARC))
        assertSame(StardustMoteTuning.LENS_COLOR, StardustMoteTuning.colorForProj(StardustMoteIds.PROJ_LENS))
        assertTrue(StardustMoteIds.isStardustProj(StardustMoteIds.PROJ_ARC))
        assertTrue(StardustMoteIds.isStardustProj(StardustMoteIds.PROJ_LENS))
        assertFalse(StardustMoteIds.isStardustProj("mote"))
    }
}
