package cn.kasuminova.astd.combat.effect.arc.signature.tsm

import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.OnHitEffectPlugin
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.min

/**
 * TSM-2 终端冲刺打击系统：双重打击 OnHitEffect。
 *
 * 机制概述：
 * - 第一击：短暂破盾/穿透窗口（0.20–0.35s）
 * - 第二击：延迟的高爆伤害（斩杀系数加成）
 *
 * 双击间隔：0.05–0.20s
 * 斩杀系数：基于目标幅能/船体计算动态倍率
 */
class Tsm2TerminalStrikeOnHitEffect : OnHitEffectPlugin {

    companion object {
        /** 双击间隔 */
        private const val SECOND_HIT_DELAY = 0.12f

        /** 护盾过载后“继续前进”的推进距离（用于把第二击落点推进到船体内侧，模拟穿透） */
        private const val OVERLOAD_PENETRATION_MIN = 35f
        private const val OVERLOAD_PENETRATION_MAX = 120f

        /** 斩杀系数：高幅能贡献 */
        private const val FLUX_COEFF = 0.35f

        /** 斩杀系数：低船体贡献 */
        private const val HULL_COEFF = 0.60f

        /** 斩杀系数上限 */
        private const val MAX_MULT = 1.75f

        /** 尺寸修正：驱逐 */
        private const val DESTROYER_MULT = 0.75f

        /** 尺寸修正：护卫 */
        private const val FRIGATE_MULT = 0.60f

        /** 破盾特效颜色 */
        private val BREACH_COLOR = Color(150, 200, 255, 200)

        /** 第二击：与弹体一致的蓝白冲击色 */
        private val STRIKE_CORE_COLOR = Color(200, 235, 255, 235)
        private val STRIKE_FRINGE_COLOR = Color(120, 200, 255, 200)
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

        // 方向：用于冲击/烟雾一致的“同轨迹”
        val impactFacing = try {
            projectile.facing
        } catch (_: Throwable) {
            0f
        }

        // 修复：若本次命中直接摧毁目标（ship.isHulk 已为 true），仍应触发命中特效。
        // 注意：此时不再安排第二击/不做额外 applyDamage。
        if (ship.isHulk) {
            val smoke = Color(STRIKE_FRINGE_COLOR.red, STRIKE_FRINGE_COLOR.green, STRIKE_FRINGE_COLOR.blue, 90)
            TsmTerminalStrikeFx.spawnImpactFx(
                engine = engine,
                point = point,
                towardTargetFacing = impactFacing,
                facingMode = TsmTerminalStrikeFx.ImpactFacingMode.OUTWARD,
                smokeColor = smoke,
                coreColor = STRIKE_CORE_COLOR,
                fringeColor = STRIKE_FRINGE_COLOR,
                intensityMult = 1.2f,
                sprayStyle = TsmTerminalStrikeFx.ImpactSprayStyle(
                    impactScale = 0.75f,
                ),
            )
            return
        }

        val overloadByThisHit = shieldHit && isShieldOverloadedByThisHit(damageResult)

        // 面板展示=第二击：直击造成的伤害/EMP 即为本次弹体命中已造成的数值。
        // 额外伤害将在下面按倍率“补齐”。
        val basePanelDamage = sanitizeNonNegativeFinite(projectile.damageAmount)
        val basePanelEmp = sanitizeNonNegativeFinite(damageResult.empDamage)

        // 不再强制关闭护盾：只保留“命中盾面”的可读反馈
        if (shieldHit && ship.shield != null) {
            val sprayFacing = (impactFacing + 180f) % 360f
            spawnShieldImpactFx(engine, ship, point, sprayFacing)
        }

        // 安排第二击
        val strikePoint = if (overloadByThisHit) {
            val p2 = computePostOverloadStrikePoint(projectile, ship, point)
            spawnOverloadPassThroughFx(engine, point, p2, impactFacing)
            p2
        } else {
            point
        }

        scheduleSecondStrike(
            projectile = projectile,
            ship = ship,
            point = strikePoint,
            impactFacing = impactFacing,
            basePanelDamage = basePanelDamage,
            basePanelEmp = basePanelEmp,
            overloadedByThisHit = overloadByThisHit,
            engine = engine,
        )
    }

    private fun spawnShieldImpactFx(engine: CombatEngineAPI, ship: ShipAPI, point: Vector2f, facing: Float) {
        // 需求：移除爆闪，改为同色烟雾，并沿冲击方向喷出。
        val smoke = Color(BREACH_COLOR.red, BREACH_COLOR.green, BREACH_COLOR.blue, 85)
        TsmTerminalStrikeFx.spawnImpactSmoke(
            engine = engine,
            point = point,
            facing = facing,
            smokeColor = smoke,
            intensityMult = 1.0f,
            puffCountBase = 5,
            puffCountExtra = 3,
            sizeMin = 55f,
            sizeMax = 105f,
            speedMin = 70f,
            speedMax = 160f,
            durationMin = 0.40f,
            durationMax = 0.75f,
        )
        ship.setJitter(ship, BREACH_COLOR, 0.35f, 4, 6f)
    }

    private fun isShieldOverloadedByThisHit(damageResult: ApplyDamageResultAPI): Boolean {
        // 该字段在 SS 0.98 API 中存在：用于表示“护盾吸收超出上限的部分”。
        // 实战上它是判断“本次命中导致过载/溢出”的最稳妥信号（不依赖 ship.fluxTracker 的具体 API 版本）。
        val over = damageResult.overMaxDamageToShields
        val dealt = damageResult.damageToShields
        return over.isFinitePositive() && over > 0f && dealt.isFinitePositive() && dealt > 0f
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

        val advance = (ship.collisionRadius * 0.45f).coerceIn(OVERLOAD_PENETRATION_MIN, OVERLOAD_PENETRATION_MAX)
        val p2 = Vector2f(point.x + dir.x * advance, point.y + dir.y * advance)
        return if (p2.x.isFinite() && p2.y.isFinite()) p2 else Vector2f(point)
    }

    private fun spawnOverloadPassThroughFx(engine: CombatEngineAPI, from: Vector2f, to: Vector2f, facing: Float) {
        val steps = 6
        for (i in 1..steps) {
            val t = i.toFloat() / (steps + 1).toFloat()
            val p = Vector2f(
                from.x + (to.x - from.x) * t,
                from.y + (to.y - from.y) * t,
            )
            val jitter = MathUtils.getRandomNumberInRange(-6f, 6f)
            val v = MathUtils.getPointOnCircumference(null, MathUtils.getRandomNumberInRange(40f, 90f), facing + jitter)
            engine.addSmoothParticle(p, v, MathUtils.getRandomNumberInRange(18f, 28f), 1.35f, 0.16f, STRIKE_FRINGE_COLOR)
        }
    }

    private fun Float.isFinitePositive(): Boolean = !this.isNaN() && !this.isInfinite() && this >= 0f

    private fun sanitizeNonNegativeFinite(v: Float): Float {
        if (v.isNaN() || v.isInfinite()) return 0f
        if (v < 0f) return 0f
        return v
    }

    private fun scheduleSecondStrike(
        projectile: DamagingProjectileAPI,
        ship: ShipAPI,
        point: Vector2f,
        impactFacing: Float,
        basePanelDamage: Float,
        basePanelEmp: Float,
        overloadedByThisHit: Boolean,
        engine: CombatEngineAPI,
    ) {
        val source = (projectile as? MissileAPI)?.source

        engine.addPlugin(object : com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin() {
            private var timer = SECOND_HIT_DELAY

            override fun advance(amount: Float, events: MutableList<com.fs.starfarer.api.input.InputEventAPI>?) {
                if (engine.isPaused) return
                timer -= amount
                if (timer <= 0f) {
                    executeSecondStrike(
                        ship = ship,
                        point = point,
                        impactFacing = impactFacing,
                        engine = engine,
                        source = source,
                        basePanelDamage = basePanelDamage,
                        basePanelEmp = basePanelEmp,
                        overloadedByThisHit = overloadedByThisHit,
                    )
                    engine.removePlugin(this)
                }
            }
        })
    }

    private fun executeSecondStrike(
        ship: ShipAPI,
        point: Vector2f,
        impactFacing: Float,
        engine: CombatEngineAPI,
        source: ShipAPI?,
        basePanelDamage: Float,
        basePanelEmp: Float,
        overloadedByThisHit: Boolean,
    ) {
        if (ship.isHulk || !engine.isEntityInPlay(ship)) {
            // 修复：击杀/移除也要有“第二击反馈”（至少 VFX），否则看起来像没触发。
            val smoke = Color(STRIKE_FRINGE_COLOR.red, STRIKE_FRINGE_COLOR.green, STRIKE_FRINGE_COLOR.blue, 90)
            TsmTerminalStrikeFx.spawnImpactFx(
                engine = engine,
                point = point,
                towardTargetFacing = impactFacing,
                facingMode = TsmTerminalStrikeFx.ImpactFacingMode.OUTWARD,
                smokeColor = smoke,
                coreColor = STRIKE_CORE_COLOR,
                fringeColor = STRIKE_FRINGE_COLOR,
                intensityMult = if (overloadedByThisHit) 2f else 1.2f,
                sprayStyle = TsmTerminalStrikeFx.ImpactSprayStyle(
                    impactScale = 0.75f,
                ),
            )
            return
        }

        val baseDamage = sanitizeNonNegativeFinite(basePanelDamage)
        val baseEmp = sanitizeNonNegativeFinite(basePanelEmp)

        // 计算额外倍率（乘算在面板伤害上）
        // - 直击：伤害=面板（已由弹体本身造成）
        // - 若盾命中并导致过载：基础伤害变为 200%（额外补 100%）
        // - 额外伤害（斩杀系数）乘算在面板伤害上
        // - 尺寸修正只作用于“额外部分”，避免直击低于面板
        val execMult = calculateExecutionMult(ship) // 1..MAX
        val sizeMult = getSizeMult(ship)            // 0.6/0.75/1.0
        val totalMult = 1f + (execMult - 1f) * sizeMult
        val overloadBase = if (overloadedByThisHit) 2f else 1f

        // 视觉强度：以当前特效大小为基础，按“伤害加成倍率”线性放大（合理封顶，避免刷屏）
        val vfxMult = (overloadBase * totalMult).coerceIn(1f, 3.0f)

        val desiredDamage = (baseDamage * overloadBase * totalMult).coerceAtLeast(0f)
        val desiredEmp = (baseEmp * overloadBase * totalMult).coerceAtLeast(0f)

        // 只施加“额外部分”（直击部分已存在）
        val extraDamage = (desiredDamage - baseDamage).coerceAtLeast(0f)
        val extraEmp = (desiredEmp - baseEmp).coerceAtLeast(0f)

        // 若没有额外伤害，也保留二段爆闪提示（但不再额外 applyDamage）
        if (extraDamage <= 0f && extraEmp <= 0f) {
            val sprayFacing = (impactFacing + 180f) % 360f
            val smoke = Color(STRIKE_FRINGE_COLOR.red, STRIKE_FRINGE_COLOR.green, STRIKE_FRINGE_COLOR.blue, 85)
            TsmTerminalStrikeFx.spawnImpactSmoke(
                engine = engine,
                point = point,
                facing = sprayFacing,
                smokeColor = smoke,
                intensityMult = vfxMult,
                puffCountBase = 6,
                puffCountExtra = 4,
            )
            return
        }

        // 应用额外伤害
        engine.applyDamage(
            ship,
            point,
            extraDamage,
            com.fs.starfarer.api.combat.DamageType.HIGH_EXPLOSIVE,
            0f,
            false,  // 不穿盾（此时盾应该还在恢复中）
            false,
            source,
        )

        // 命中反馈：“冲击喷散条纹”（盾命中也要触发，提升可读性）
        val onShieldNow = try {
            val s = ship.shield
            s != null && s.isOn && s.isWithinArc(point)
        } catch (_: Throwable) {
            false
        }
        // 朝来袭方向的反向喷散（更像“冲击波把碎光往后甩出去”）
        // 需求：移除爆闪，改为同色爆炸烟雾，并与冲击同轨迹
        val smoke = Color(STRIKE_FRINGE_COLOR.red, STRIKE_FRINGE_COLOR.green, STRIKE_FRINGE_COLOR.blue, 90)
        TsmTerminalStrikeFx.spawnImpactFx(
            engine = engine,
            point = point,
            towardTargetFacing = impactFacing,
            facingMode = TsmTerminalStrikeFx.ImpactFacingMode.OUTWARD,
            smokeColor = smoke,
            coreColor = STRIKE_CORE_COLOR,
            fringeColor = STRIKE_FRINGE_COLOR,
            intensityMult = vfxMult,
            sprayStyle = TsmTerminalStrikeFx.ImpactSprayStyle(
                impactScale = 0.75f,
            ),
        )

        // EMP：优先打击武器与引擎，并提供电弧可读性
        spawnSubsystemEmpArcs(
            engine = engine,
            target = ship,
            center = point,
            totalEmp = extraEmp,
            pierceShields = onShieldNow,
            source = source,
            coreColor = STRIKE_CORE_COLOR,
            fringeColor = STRIKE_FRINGE_COLOR,
        )

        // EMP 火花（可选）：随伤害加成放大数量与范围
        val sparkCount = (6f * vfxMult).toInt().coerceIn(6, 20)
        for (i in 0 until sparkCount) {
            val sparkDir = org.lazywizard.lazylib.MathUtils.getRandomNumberInRange(0f, 360f)
            val sparkDist = org.lazywizard.lazylib.MathUtils.getRandomNumberInRange(20f, 50f) * (0.85f + 0.15f * vfxMult)
            val sparkPos = org.lazywizard.lazylib.MathUtils.getPointOnCircumference(point, sparkDist, sparkDir)
            engine.addSmoothParticle(
                sparkPos,
                Vector2f(
                    org.lazywizard.lazylib.MathUtils.getRandomNumberInRange(-80f, 80f),
                    org.lazywizard.lazylib.MathUtils.getRandomNumberInRange(-80f, 80f)
                ),
                org.lazywizard.lazylib.MathUtils.getRandomNumberInRange(4f, 8f) * (0.85f + 0.15f * vfxMult),
                1.2f,
                0.15f,
                Color(180, 220, 255, 200),
            )
        }

        // 添加强烈抖动
        ship.setJitter(ship, STRIKE_FRINGE_COLOR, 0.6f, 6, 10f)
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
        )
    }

    /**
     * 计算斩杀系数：M = 1 + 0.35 * F + 0.60 * H，且 M ≤ 1.75
     * - F = clamp((flux - 0.6) / 0.4, 0, 1)
     * - H = clamp((0.55 - hull) / 0.55, 0, 1)
     */
    private fun calculateExecutionMult(ship: ShipAPI): Float {
        val fluxRatio = ship.fluxLevel
        val hullRatio = ship.hullLevel

        val f = ((fluxRatio - 0.6f) / 0.4f).coerceIn(0f, 1f)
        val h = ((0.55f - hullRatio) / 0.55f).coerceIn(0f, 1f)

        val mult = 1f + FLUX_COEFF * f + HULL_COEFF * h
        return min(mult, MAX_MULT)
    }

    private fun getSizeMult(ship: ShipAPI): Float {
        return when (ship.hullSize) {
            ShipAPI.HullSize.FRIGATE -> FRIGATE_MULT
            ShipAPI.HullSize.DESTROYER -> DESTROYER_MULT
            else -> 1.0f
        }
    }
}
