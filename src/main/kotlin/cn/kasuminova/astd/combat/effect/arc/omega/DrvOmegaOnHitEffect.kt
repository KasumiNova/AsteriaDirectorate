package cn.kasuminova.astd.combat.effect.arc.omega

import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.OnHitEffectPlugin
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI
import org.lazywizard.lazylib.combat.entities.SimpleEntity
import org.lazywizard.lazylib.MathUtils
import org.lazywizard.lazylib.VectorUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

/**
 * DRV-Ω 相对论聚能炮：命中 VFX。
 *
 * 每次弹体命中（无论护盾/装甲）触发：
 * - 从武器炮口到命中点的瞬间闪光束（伪超速光束）
 * - 命中点环状激波 + 扭曲坍缩（"抵达即毁灭"反馈）
 *
 * 注意：机制部分（强制过载 / Flux Injection）尚未实现，将在后续迭代中添加。
 */
class DrvOmegaOnHitEffect : OnHitEffectPlugin {

    override fun onHit(
        projectile: DamagingProjectileAPI,
        target: CombatEntityAPI,
        point: Vector2f,
        shieldHit: Boolean,
        damageResult: ApplyDamageResultAPI,
        engine: CombatEngineAPI,
    ) {
        val from = resolveWeaponBarrelLocation(projectile, point)
        val to = resolveImpactPoint(projectile, point)

        val isFinisher = try {
            projectile.customData[DrvOmegaSlugInstantOnSpawn.KEY_FINISHER] as? Boolean
        } catch (_: Throwable) {
            null
        } ?: false

        DrvOmegaImpactVfx.spawnFullImpact(
            engine,
            from,
            to,
            shieldHit = shieldHit,
            theme = if (isFinisher) DrvOmegaImpactVfx.Theme.REDSHIFT else DrvOmegaImpactVfx.Theme.CYAN,
        )

        if (isFinisher) {
            spawnFinisherEmpArcs(engine, projectile, target, to, shieldHit)
        }
    }

    companion object {

        /** 回退距离：当无法获取武器位置时，沿弹体反向追溯此距离作为闪光束起点。 */
        private const val FALLBACK_TRACE_DISTANCE = 700f

        /**
         * 推算武器炮口位置。
         * 优先使用 [DamagingProjectileAPI.getWeapon] 的位置（DRV-Ω 的 turretOffsets=[0,0]，故 weapon.location 即炮口）。
         * 若不可用，则沿弹体速度方向反推 [FALLBACK_TRACE_DISTANCE]。
         */
        private fun resolveWeaponBarrelLocation(projectile: DamagingProjectileAPI, hitPoint: Vector2f): Vector2f {
            // 优先：onFire 预计算的起点（更稳定，且不受“近似传送”影响）
            try {
                val cd = projectile.customData
                val from = cd[DrvOmegaSlugInstantOnSpawn.KEY_FROM] as? Vector2f
                if (from != null) return Vector2f(from)
            } catch (_: Throwable) {
            }

            val weapon = projectile.weapon
            if (weapon != null) return Vector2f(weapon.location)

            // 沿弹体飞行方向反推
            val vel = projectile.velocity
            val facing = if (vel != null && vel.x * vel.x + vel.y * vel.y > 0.01f) {
                VectorUtils.getFacing(vel)
            } else {
                projectile.facing
            }
            return MathUtils.getPointOnCircumference(hitPoint, FALLBACK_TRACE_DISTANCE, facing + 180f)
        }

        /**
         * 命中点（用于 VFX）：优先使用 onFire 预计算的落点（更贴近“弹道光束”预期）。
         *
         * 说明：由于我们可能对弹体做了“近似传送”，onHit 的 point 有时会略偏离预判落点。
         */
        private fun resolveImpactPoint(projectile: DamagingProjectileAPI, fallback: Vector2f): Vector2f {
            try {
                val cd = projectile.customData
                val to = cd[DrvOmegaSlugInstantOnSpawn.KEY_TO] as? Vector2f
                if (to != null) return Vector2f(to)
            } catch (_: Throwable) {
            }
            return Vector2f(fallback)
        }

        private val RED_FRINGE = Color(255, 70, 70, 255)
        private val RED_CORE = Color(255, 190, 190, 255)

        private fun spawnFinisherEmpArcs(
            engine: CombatEngineAPI,
            projectile: DamagingProjectileAPI,
            target: CombatEntityAPI,
            point: Vector2f,
            shieldHit: Boolean,
        ) {
            val source = try { projectile.source } catch (_: Throwable) { null } ?: return

            val baseDamage = try {
                projectile.customData[DrvOmegaSlugInstantOnSpawn.KEY_BASE_DAMAGE] as? Float
            } catch (_: Throwable) {
                null
            } ?: try {
                // fallback：customData 丢失时，终结技已被放大 3 倍，除回得到面板伤害。
                projectile.damageAmount / 3f
            } catch (_: Throwable) { 0f }

            if (baseDamage <= 0.01f) return

            val arcs = 3 + (Math.random() * 3).toInt() // 3..5
            for (i in 0 until arcs) {
                val start = MathUtils.getRandomPointInCircle(point, 18f)

                // 若命中护盾：给穿盾概率；否则默认不穿
                val pierce = shieldHit && MathUtils.getRandomNumberInRange(0f, 1f) < 0.35f

                try {
                    if (!pierce) {
                        engine.spawnEmpArc(
                            source,
                            start,
                            target,
                            target,
                            DamageType.ENERGY,
                            baseDamage,
                            baseDamage,
                            1_000_000f,
                            "tachyon_lance_emp_impact",
                            MathUtils.getRandomNumberInRange(28f, 44f),
                            RED_FRINGE,
                            RED_CORE,
                        )
                    } else {
                        engine.spawnEmpArcPierceShields(
                            source,
                            start,
                            null,
                            target,
                            DamageType.ENERGY,
                            baseDamage,
                            baseDamage,
                            1_000_000f,
                            "tachyon_lance_emp_impact",
                            MathUtils.getRandomNumberInRange(28f, 44f),
                            RED_FRINGE,
                            RED_CORE,
                        )
                    }
                } catch (_: Throwable) {
                }

                // 少量“放电到空气”的视觉补偿（不伤害）
                if (MathUtils.getRandomNumberInRange(0f, 1f) < 0.35f) {
                    val p2 = MathUtils.getRandomPointInCircle(point, 140f)
                    try {
                        engine.spawnEmpArcVisual(
                            start,
                            source,
                            p2,
                            SimpleEntity(p2),
                            MathUtils.getRandomNumberInRange(10f, 16f),
                            Color(255, 70, 70, 185),
                            Color(255, 210, 210, 205),
                        )
                    } catch (_: Throwable) {
                    }
                }
            }
        }
    }
}
