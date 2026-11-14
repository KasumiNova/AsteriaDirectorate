package cn.kasuminova.astd.combat.shipsystems

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GressionChargeEngagementTest {

    private fun travelSample(
        destDistance: Float = 2400f,
        headingDeltaDeg: Float = 4f,
        accelerating: Boolean = true,
        decelerating: Boolean = false,
        angularVelocityDeg: Float = 2f,
        enemyNearby: Boolean = false,
        runningQuickly: Boolean = false,
        retreating: Boolean = false,
        pursuing: Boolean = false,
        sprintSpeed: Float = SPRINT_SPEED,
        hardFluxLevel: Float = 0.2f,
    ): GressionChargeEngagement.Sample = GressionChargeEngagement.Sample(
        hasTarget = false,
        facingDeltaDeg = 180f,
        surfaceDistance = 0f,
        predictedSurfaceDistance = 0f,
        aimAngularRadiusDeg = 0f,
        sprintSpeed = sprintSpeed,
        hardFluxLevel = hardFluxLevel,
        angularVelocityDeg = angularVelocityDeg,
        pursuing = pursuing,
        travel = GressionChargeEngagement.Travel(
            destDistance = destDistance,
            headingDeltaDeg = headingDeltaDeg,
            accelerating = accelerating,
            decelerating = decelerating,
            enemyNearby = enemyNearby,
            runningQuickly = runningQuickly,
            retreating = retreating,
        ),
    )

    private fun engageSample(
        facingDeltaDeg: Float = 8f,
        surfaceDistance: Float = 700f,
        predictedSurfaceDistance: Float = 520f,
        aimAngularRadiusDeg: Float = 0f,
        sprintSpeed: Float = SPRINT_SPEED,
        hardFluxLevel: Float = 0.2f,
        angularVelocityDeg: Float = 4f,
        hasTarget: Boolean = true,
    ): GressionChargeEngagement.Sample = GressionChargeEngagement.Sample(
        hasTarget = hasTarget,
        facingDeltaDeg = facingDeltaDeg,
        surfaceDistance = surfaceDistance,
        predictedSurfaceDistance = predictedSurfaceDistance,
        aimAngularRadiusDeg = aimAngularRadiusDeg,
        sprintSpeed = sprintSpeed,
        hardFluxLevel = hardFluxLevel,
        angularVelocityDeg = angularVelocityDeg,
        pursuing = false,
        travel = null,
    )

    @Test
    fun `无交战目标时对准远处目的地起手赶路`() {
        assertTrue(GressionChargeEngagement.shouldActivate(travelSample()))
        assertFalse(
            GressionChargeEngagement.shouldActivate(
                GressionChargeEngagement.Sample(
                    hasTarget = false,
                    facingDeltaDeg = 180f,
                    surfaceDistance = 0f,
                    predictedSurfaceDistance = 0f,
                    aimAngularRadiusDeg = 0f,
                    sprintSpeed = SPRINT_SPEED,
                    hardFluxLevel = 0.2f,
                    angularVelocityDeg = 0f,
                    pursuing = false,
                    travel = null,
                ),
            ),
            "没有目标也没有赶路指令时不起手",
        )
    }

    @Test
    fun `背对目的地或背对目标不起手`() {
        assertFalse(GressionChargeEngagement.shouldActivate(travelSample(headingDeltaDeg = 170f)))
        assertFalse(GressionChargeEngagement.shouldActivate(engageSample(facingDeltaDeg = 170f)))
    }

    @Test
    fun `对准目标短期预测位置才进攻`() {
        assertTrue(GressionChargeEngagement.shouldActivate(engageSample()))
        assertFalse(
            GressionChargeEngagement.shouldActivate(engageSample(predictedSurfaceDistance = 690f)),
            "预测表面间距没有明显缩短，说明目标在拉开距离",
        )
        assertFalse(
            GressionChargeEngagement.shouldActivate(
                engageSample(surfaceDistance = 1600f, predictedSurfaceDistance = 1500f),
            ),
            "表面间距超过一次冲刺的可达距离",
        )
    }

    @Test
    fun `大舰角半径放宽瞄准上限为固定增量`() {
        assertTrue(GressionChargeEngagement.shouldActivate(engageSample(facingDeltaDeg = 20f, aimAngularRadiusDeg = 8f)))
        assertFalse(GressionChargeEngagement.shouldActivate(engageSample(facingDeltaDeg = 20f)))
        assertFalse(
            GressionChargeEngagement.shouldActivate(engageSample(facingDeltaDeg = 30f, aimAngularRadiusDeg = 60f)),
            "角半径只提供有限容差，不能无限放宽",
        )
    }

    @Test
    fun `近距离 高硬辐能与高速转向不起手`() {
        assertFalse(GressionChargeEngagement.shouldActivate(engageSample(surfaceDistance = 60f, predictedSurfaceDistance = 0f)))
        assertFalse(GressionChargeEngagement.shouldActivate(engageSample(hardFluxLevel = 0.9f)))
        assertFalse(GressionChargeEngagement.shouldActivate(engageSample(angularVelocityDeg = 40f)))
        assertTrue(
            GressionChargeEngagement.shouldActivate(engageSample(hardFluxLevel = 0.62f)),
            "冲刺自带减伤，硬辐能占用略高仍允许起手",
        )
    }

    @Test
    fun `赶路要求持续加速且航向稳定`() {
        assertFalse(GressionChargeEngagement.shouldActivate(travelSample(accelerating = false)))
        assertFalse(GressionChargeEngagement.shouldActivate(travelSample(decelerating = true)))
        assertFalse(GressionChargeEngagement.shouldActivate(travelSample(angularVelocityDeg = 30f)))
        assertFalse(GressionChargeEngagement.shouldActivate(travelSample(destDistance = 900f)))
        assertFalse(
            GressionChargeEngagement.shouldActivate(travelSample(destDistance = 2400f, sprintSpeed = 1200f)),
            "目的地比一次冲刺还近时不赶路，避免冲过头",
        )
    }

    @Test
    fun `战区内有敌舰时只有撤离或急行才赶路`() {
        assertFalse(GressionChargeEngagement.shouldActivate(travelSample(enemyNearby = true)))
        assertTrue(GressionChargeEngagement.shouldActivate(travelSample(enemyNearby = true, runningQuickly = true)))
        assertTrue(GressionChargeEngagement.shouldActivate(travelSample(enemyNearby = true, retreating = true)))
        assertFalse(GressionChargeEngagement.shouldActivate(travelSample(pursuing = true)))
        assertTrue(GressionChargeEngagement.shouldActivate(travelSample(pursuing = true, retreating = true)))
    }

    companion object {
        private const val SPRINT_SPEED = 450f
    }
}
