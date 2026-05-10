package cn.kasuminova.astd.combat.effect.arc.arc13

import cn.kasuminova.astd.renderer.effect.projectile.beam.OglEllipseRingRenderer
import cn.kasuminova.astd.combat.effect.arc.signature.tsm.TsmTerminalStrikeFx
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamageType
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

internal object Arc13Vfx {

    fun spawnMuzzlePulse(engine: CombatEngineAPI, from: Vector2f, facing: Float, theme: Color) {
        try {
            OglEllipseRingRenderer.spawn(
                engine,
                OglEllipseRingRenderer.RingSpec(
                    center = from,
                    facing = facing,
                    aSideHalf = 26f,
                    bAlongHalf = 12f,
                    duration = 0.22f,
                    color = Color(theme.red, theme.green, theme.blue, 140),
                    lineWidthPx = 1.35f,
                    segments = 72,
                    expandSpeed = 220f,
                    tangentialSpeed = 0f,
                ),
            )
        } catch (_: Throwable) {
        }

        try {
            OglEllipseRingRenderer.spawn(
                engine,
                OglEllipseRingRenderer.RingSpec(
                    center = from,
                    facing = facing + 45f,
                    aSideHalf = 18f,
                    bAlongHalf = 9f,
                    duration = 0.18f,
                    color = Color(theme.red, theme.green, theme.blue, 90),
                    lineWidthPx = 1.15f,
                    segments = 64,
                    expandSpeed = 300f,
                    tangentialSpeed = -2.0f,
                ),
            )
        } catch (_: Throwable) {
        }
    }

    fun spawnImpact(engine: CombatEngineAPI, point: Vector2f, facing: Float, damageType: DamageType, shieldHit: Boolean) {
        when (damageType) {
            DamageType.KINETIC -> spawnImpactKinetic(engine, point, facing, shieldHit)
            DamageType.HIGH_EXPLOSIVE -> spawnImpactHe(engine, point, facing)
            DamageType.FRAGMENTATION -> spawnImpactFrag(engine, point, facing)
            else -> spawnImpactKinetic(engine, point, facing, shieldHit)
        }
    }

    private fun spawnImpactKinetic(engine: CombatEngineAPI, p: Vector2f, facing: Float, shieldHit: Boolean) {
        // 需求：动能相使用 TSM 冲击特效；击中船体反转方向（INWARD），护盾保持 OUTWARD
        try {
            TsmTerminalStrikeFx.spawnImpactFx(
                engine = engine,
                point = p,
                towardTargetFacing = facing,
                facingMode = if (shieldHit) TsmTerminalStrikeFx.ImpactFacingMode.OUTWARD else TsmTerminalStrikeFx.ImpactFacingMode.INWARD,
                smokeColor = Color(120, 220, 255, 60),
                coreColor = Color(220, 250, 255, 235),
                fringeColor = Color(120, 220, 255, 215),
                intensityMult = 0.80f,
                sprayStyle = TsmTerminalStrikeFx.ImpactSprayStyle(
                    impactScale = 0.70f,
                    baseRaysMin = 10,
                    baseRaysExtra = 6,
                    widthMin = 5f,
                    widthMax = 11f,
                    lengthMin = 90f,
                    lengthMax = 220f,
                    speedMin = 220f * 0.34f,
                    speedMax = 520f * 0.34f,
                    fullMin = 0.06f * 0.75f,
                    fullMax = 0.12f * 0.75f,
                    fadeOutMin = 0.44f * 0.75f,
                    fadeOutMax = 0.64f * 0.75f,
                ),
                smokeStyle = TsmTerminalStrikeFx.ImpactSmokeStyle(
                    puffCountBase = 2,
                    puffCountExtra = 2,
                    sizeMin = 36f,
                    sizeMax = 80f,
                    durationMin = 0.26f * 0.75f,
                    durationMax = 0.52f * 0.75f,
                    speedMin = 70f * 0.34f,
                    speedMax = 160f * 0.34f,
                ),
            )
        } catch (_: Throwable) {
        }

        // 叠一层稳定的扩散环，增强读感（不会抢戏）
        try {
            OglEllipseRingRenderer.spawn(
                engine,
                OglEllipseRingRenderer.RingSpec(
                    center = p,
                    facing = facing,
                    aSideHalf = 28f,
                    bAlongHalf = 16f,
                    duration = 0.28f,
                    color = Color(120, 220, 255, 110),
                    lineWidthPx = 1.35f,
                    segments = 72,
                    expandSpeed = 160f,
                    tangentialSpeed = 0f,
                ),
            )
        } catch (_: Throwable) {
        }
    }

    private fun spawnImpactHe(engine: CombatEngineAPI, p: Vector2f, facing: Float) {
        try {
            engine.spawnExplosion(p, Vector2f(), Color(255, 200, 120, 220), 120f, 0.22f)
        } catch (_: Throwable) {
        }

        try {
            OglEllipseRingRenderer.spawn(
                engine,
                OglEllipseRingRenderer.RingSpec(
                    center = p,
                    facing = facing,
                    aSideHalf = 34f,
                    bAlongHalf = 22f,
                    duration = 0.34f,
                    color = Color(255, 170, 80, 140),
                    lineWidthPx = 1.6f,
                    segments = 72,
                    expandSpeed = 140f,
                    tangentialSpeed = 0f,
                ),
            )
        } catch (_: Throwable) {
        }

        try {
            repeat(4) {
                val v = MathUtils.getPointOnCircumference(null, MathUtils.getRandomNumberInRange(30f, 90f), MathUtils.getRandomNumberInRange(0f, 360f))
                engine.addNebulaParticle(p, v, MathUtils.getRandomNumberInRange(50f, 90f), 1.4f, 0.25f, 0.55f, MathUtils.getRandomNumberInRange(0.35f, 0.55f), Color(255, 160, 90, 70))
            }
        } catch (_: Throwable) {
        }
    }

    private fun spawnImpactFrag(engine: CombatEngineAPI, p: Vector2f, facing: Float) {
        try {
            OglEllipseRingRenderer.spawn(
                engine,
                OglEllipseRingRenderer.RingSpec(
                    center = p,
                    facing = facing,
                    aSideHalf = 32f,
                    bAlongHalf = 20f,
                    duration = 0.36f,
                    color = Color(190, 150, 255, 135),
                    lineWidthPx = 1.45f,
                    segments = 80,
                    expandSpeed = 160f,
                    tangentialSpeed = 1.2f,
                ),
            )
        } catch (_: Throwable) {
        }

        try {
            // 破片：更多细小粒子
            repeat(14) {
                val v = MathUtils.getPointOnCircumference(null, MathUtils.getRandomNumberInRange(80f, 240f), MathUtils.getRandomNumberInRange(0f, 360f))
                engine.addHitParticle(p, v, 16f, 1.0f, 0.22f, Color(220, 200, 255, 150))
            }
        } catch (_: Throwable) {
        }
    }
}
