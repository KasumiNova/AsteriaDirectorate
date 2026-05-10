package cn.kasuminova.astd.combat.effect.arc.signature.tsm

import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.OnHitEffectPlugin
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI
import org.lazywizard.lazylib.MathUtils
import org.lazywizard.lazylib.combat.CombatUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

/**
 * TSM-Ω 终端裁决打击系统：
 * - 固定 5000 能量伤害（弹体本体）；若本次命中盾面并导致过载，则继续前进并在船体内侧追加一次 5000 能量伤害。
 * - 命中点释放范围高额 EMP（优先打击武器/引擎），并带紫色电弧可读性。
 */
class TsmOmegaTerminalVerdictOnHitEffect : OnHitEffectPlugin {

    companion object {
        private const val SECOND_HIT_DELAY = 0.12f

        private const val OVERLOAD_PENETRATION_MIN = 45f
        private const val OVERLOAD_PENETRATION_MAX = 150f

        /** 追加伤害：破盾后直击船体（固定） */
        private const val HULL_VERDICT_DAMAGE = 5000f

        /** 范围 EMP（每艘目标的基准值；随距离线性衰减） */
        private const val AOE_EMP_RADIUS = 520f
        private const val AOE_EMP_AT_CENTER = 6000f

        private val SHIELD_BREACH_COLOR = Color(210, 150, 255, 210)
        private val STRIKE_CORE_COLOR = Color(245, 225, 255, 245)
        private val STRIKE_FRINGE_COLOR = Color(180, 110, 255, 235)
    }

    override fun onHit(
        projectile: DamagingProjectileAPI,
        target: CombatEntityAPI,
        point: Vector2f,
        shieldHit: Boolean,
        damageResult: ApplyDamageResultAPI,
        engine: CombatEngineAPI,
    ) {
        val ship = target as? ShipAPI ?: return

        val source = (projectile as? MissileAPI)?.source
        val overloadByThisHit = shieldHit && isShieldOverloadedByThisHit(damageResult)

        val impactFacing = try {
            projectile.facing
        } catch (_: Throwable) {
            0f
        }

        // 修复：本次命中直接摧毁目标时，仍应触发命中特效。
        // 注意：此时不再安排二段，但仍释放 AOE EMP（表现更像“裁决爆发”）。
        if (ship.isHulk) {
            val smoke = Color(STRIKE_FRINGE_COLOR.red, STRIKE_FRINGE_COLOR.green, STRIKE_FRINGE_COLOR.blue, 95)
            TsmTerminalStrikeFx.spawnImpactFx(
                engine = engine,
                point = point,
                towardTargetFacing = impactFacing,
                facingMode = TsmTerminalStrikeFx.ImpactFacingMode.OUTWARD,
                smokeColor = smoke,
                coreColor = STRIKE_CORE_COLOR,
                fringeColor = STRIKE_FRINGE_COLOR,
                intensityMult = 1.6f,
                sprayStyle = TsmTerminalStrikeFx.ImpactSprayStyle(
                    baseRaysMin = 24,
                    baseRaysExtra = 11,
                    arc = 70f,
                    lengthMin = 170f,
                    lengthMax = 360f,
                    widthMin = 14.0f,
                    widthMax = 28.0f,
                    fullMin = 0.06f,
                    fullMax = 0.13f,
                    fadeOutMin = 0.46f,
                    fadeOutMax = 0.70f,
                    speedMin = 260f,
                    speedMax = 600f,
                ),
            )
            spawnAoeEmpBurst(engine, source, ship, point)
            return
        }

        // 护盾开门反馈
        if (shieldHit && ship.shield != null) {
            val sprayFacing = (impactFacing + 180f) % 360f
            spawnShieldBreachFx(engine, ship, point, sprayFacing)
        }

        if (overloadByThisHit) {
            val p2 = computePostOverloadStrikePoint(projectile, ship, point)
            spawnOverloadPassThroughFx(engine, point, p2, impactFacing)

            scheduleSecondStrike(
                ship = ship,
                point = p2,
                impactFacing = impactFacing,
                engine = engine,
                source = source,
            )
        } else {
            // 非破盾：直接在命中点释放裁决 EMP
            spawnVerdictStrikeFx(engine, ship, point, impactFacing, 1f)
            spawnAoeEmpBurst(engine, source, ship, point)
        }
    }

    private fun isShieldOverloadedByThisHit(damageResult: ApplyDamageResultAPI): Boolean {
        val over = damageResult.overMaxDamageToShields
        val dealt = damageResult.damageToShields
        return over.isFinitePositive() && over > 0f && dealt.isFinitePositive() && dealt > 0f
    }

    private fun Float.isFinitePositive(): Boolean = !this.isNaN() && !this.isInfinite() && this >= 0f

    private fun sanitizeNonNegativeFinite(v: Float): Float {
        if (v.isNaN() || v.isInfinite()) return 0f
        if (v < 0f) return 0f
        return v
    }

    private fun computePostOverloadStrikePoint(
        projectile: DamagingProjectileAPI,
        ship: ShipAPI,
        point: Vector2f,
    ): Vector2f {
        val dir = try {
            Vector2f(projectile.velocity)
        } catch (_: Throwable) {
            Vector2f()
        }
        if (!dir.length().isFinitePositive() || dir.length() < 1f) {
            val v = MathUtils.getPointOnCircumference(
                null, 1f, try {
                    projectile.facing
                } catch (_: Throwable) {
                    0f
                }
            )
            dir.set(v)
        }

        val len = dir.length().coerceAtLeast(1f)
        dir.scale(1f / len)

        val advance = (ship.collisionRadius * 0.55f).coerceIn(OVERLOAD_PENETRATION_MIN, OVERLOAD_PENETRATION_MAX)
        val p2 = Vector2f(point.x + dir.x * advance, point.y + dir.y * advance)
        return if (p2.x.isFinite() && p2.y.isFinite()) p2 else Vector2f(point)
    }

    private fun spawnShieldBreachFx(engine: CombatEngineAPI, ship: ShipAPI, point: Vector2f, facing: Float) {
        val smoke = Color(SHIELD_BREACH_COLOR.red, SHIELD_BREACH_COLOR.green, SHIELD_BREACH_COLOR.blue, 90)
        TsmTerminalStrikeFx.spawnImpactSmoke(
            engine = engine,
            point = point,
            facing = facing,
            smokeColor = smoke,
            intensityMult = 1.0f,
            puffCountBase = 5,
            puffCountExtra = 3,
            sizeMin = 60f,
            sizeMax = 115f,
            speedMin = 80f,
            speedMax = 170f,
            durationMin = 0.42f,
            durationMax = 0.80f,
        )
        ship.setJitter(ship, SHIELD_BREACH_COLOR, 0.40f, 4, 7f)
    }

    private fun spawnOverloadPassThroughFx(engine: CombatEngineAPI, from: Vector2f, to: Vector2f, facing: Float) {
        val steps = 7
        for (i in 1..steps) {
            val t = i.toFloat() / (steps + 1).toFloat()
            val p = Vector2f(
                from.x + (to.x - from.x) * t,
                from.y + (to.y - from.y) * t,
            )
            val jitter = MathUtils.getRandomNumberInRange(-8f, 8f)
            val v = MathUtils.getPointOnCircumference(null, MathUtils.getRandomNumberInRange(60f, 120f), facing + jitter)
            engine.addSmoothParticle(p, v, MathUtils.getRandomNumberInRange(20f, 34f), 1.4f, 0.18f, STRIKE_FRINGE_COLOR)
        }
    }

    private fun scheduleSecondStrike(
        ship: ShipAPI,
        point: Vector2f,
        impactFacing: Float,
        engine: CombatEngineAPI,
        source: ShipAPI?,
    ) {
        engine.addPlugin(object : com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin() {
            private var timer = SECOND_HIT_DELAY

            override fun advance(amount: Float, events: MutableList<com.fs.starfarer.api.input.InputEventAPI>?) {
                if (engine.isPaused) return
                timer -= amount
                if (timer <= 0f) {
                    executeVerdictStrike(
                        ship = ship,
                        point = point,
                        impactFacing = impactFacing,
                        engine = engine,
                        source = source,
                    )
                    engine.removePlugin(this)
                }
            }
        })
    }

    private fun executeVerdictStrike(
        ship: ShipAPI,
        point: Vector2f,
        impactFacing: Float,
        engine: CombatEngineAPI,
        source: ShipAPI?,
    ) {
        if (ship.isHulk || !engine.isEntityInPlay(ship)) {
            val smoke = Color(STRIKE_FRINGE_COLOR.red, STRIKE_FRINGE_COLOR.green, STRIKE_FRINGE_COLOR.blue, 95)
            TsmTerminalStrikeFx.spawnImpactFx(
                engine = engine,
                point = point,
                towardTargetFacing = impactFacing,
                facingMode = TsmTerminalStrikeFx.ImpactFacingMode.OUTWARD,
                smokeColor = smoke,
                coreColor = STRIKE_CORE_COLOR,
                fringeColor = STRIKE_FRINGE_COLOR,
                intensityMult = 2f,
                sprayStyle = TsmTerminalStrikeFx.ImpactSprayStyle(
                    baseRaysMin = 24,
                    baseRaysExtra = 11,
                    arc = 70f,
                    lengthMin = 170f,
                    lengthMax = 360f,
                    widthMin = 14.0f,
                    widthMax = 28.0f,
                    fullMin = 0.06f,
                    fullMax = 0.13f,
                    fadeOutMin = 0.46f,
                    fadeOutMax = 0.70f,
                    speedMin = 260f,
                    speedMax = 600f,
                ),
            )
            return
        }

        // 破盾后追加一次“裁决直击”
        engine.applyDamage(
            ship,
            point,
            HULL_VERDICT_DAMAGE,
            com.fs.starfarer.api.combat.DamageType.ENERGY,
            0f,
            true,
            false,
            source,
        )

        // 破盾翻倍：第二击视觉按 2× 强度放大
        spawnVerdictStrikeFx(engine, ship, point, impactFacing, 2f)
        spawnAoeEmpBurst(engine, source, ship, point)
    }

    private fun spawnVerdictStrikeFx(
        engine: CombatEngineAPI,
        ship: ShipAPI,
        point: Vector2f,
        impactFacing: Float,
        intensityMult: Float,
    ) {
        val vis = intensityMult.coerceIn(1f, 3f)

        // 需求：移除爆闪，改为同色爆炸烟雾，并与冲击同轨迹
        val smoke = Color(STRIKE_FRINGE_COLOR.red, STRIKE_FRINGE_COLOR.green, STRIKE_FRINGE_COLOR.blue, 95)
        TsmTerminalStrikeFx.spawnImpactFx(
            engine = engine,
            point = point,
            towardTargetFacing = impactFacing,
            facingMode = TsmTerminalStrikeFx.ImpactFacingMode.OUTWARD,
            smokeColor = smoke,
            coreColor = STRIKE_CORE_COLOR,
            fringeColor = STRIKE_FRINGE_COLOR,
            intensityMult = vis,
            smokeStyle = TsmTerminalStrikeFx.ImpactSmokeStyle(
                puffCountBase = 7,
                puffCountExtra = 5,
                sizeMin = 75f,
                sizeMax = 145f,
                speedMin = 95f,
                speedMax = 210f,
            ),
            sprayStyle = TsmTerminalStrikeFx.ImpactSprayStyle(
                baseRaysMin = 24,
                baseRaysExtra = 11,
                arc = 70f,
                lengthMin = 170f,
                lengthMax = 360f,
                widthMin = 14.0f,
                widthMax = 28.0f,
                fullMin = 0.06f,
                fullMax = 0.13f,
                fadeOutMin = 0.46f,
                fadeOutMax = 0.70f,
                speedMin = 260f,
                speedMax = 600f,
            ),
        )

        // 轻度抖动
        ship.setJitter(ship, STRIKE_FRINGE_COLOR, 0.65f, 6, 12f * (0.75f + 0.25f * vis))
    }

    private fun spawnAoeEmpBurst(
        engine: CombatEngineAPI,
        source: ShipAPI?,
        primaryTarget: ShipAPI,
        center: Vector2f,
    ) {
        val ships = try {
            CombatUtils.getShipsWithinRange(center, AOE_EMP_RADIUS)
        } catch (_: Throwable) {
            return
        }

        for (s in ships) {
            if (s == null) continue
            if (s.isHulk) continue
            if (!engine.isEntityInPlay(s)) continue

            // 避免友伤（若无法判定来源，则默认只打 primaryTarget）
            if (source != null && s.owner == source.owner) continue
            if (source == null && s != primaryTarget) continue

            val dist = MathUtils.getDistance(center, s.location)
            val mult = ((AOE_EMP_RADIUS - dist) / AOE_EMP_RADIUS).coerceIn(0f, 1f)
            val emp = sanitizeNonNegativeFinite(AOE_EMP_AT_CENTER * mult)
            if (emp <= 0f) continue

            spawnSubsystemEmpArcs(
                engine = engine,
                target = s,
                center = center,
                totalEmp = emp,
                pierceShields = true,
                source = source,
                coreColor = STRIKE_CORE_COLOR,
                fringeColor = STRIKE_FRINGE_COLOR,
            )
        }
    }

    private fun spawnSubsystemEmpArcs(
        engine: CombatEngineAPI,
        target: ShipAPI,
        center: Vector2f,
        totalEmp: Float,
        pierceShields: Boolean,
        source: ShipAPI?,
        coreColor: Color,
        fringeColor: Color,
    ) {
        TsmTerminalStrikeFx.spawnSubsystemEmpArcs(
            engine = engine,
            target = target,
            center = center,
            totalEmp = totalEmp,
            pierceShields = pierceShields,
            source = source,
            coreColor = coreColor,
            fringeColor = fringeColor,
            empPerArcDivisor = 900f,
            arcCountMin = 3,
            arcCountMax = 9,
            arcWidthMin = 12f,
            arcWidthMax = 22f,
        )
    }

}
