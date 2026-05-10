package cn.kasuminova.astd.combat.effect.arc.production

import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.OnHitEffectPlugin
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

/**
 * VPD-6 矢量点防阵列：对导弹/战机有额外伤害倍率，对舰体伤害降低。
 *
 * 注意：由于 OnHitEffect 在伤害计算后触发，此效果通过额外伤害实现。
 */
class Vpd6PointDefenseOnHitEffect : OnHitEffectPlugin {

    companion object {
        /** 对导弹额外伤害倍率 */
        private const val MISSILE_MULT = 1.8f

        /** 对战机额外伤害倍率 */
        private const val FIGHTER_MULT = 1.5f

        /** 对舰体伤害减免 */
        private const val SHIP_MULT = 0.5f

        /** 导弹命中特效 */
        private val MISSILE_FX = Color(255, 255, 255, 200)

        /** 战机命中特效 */
        private val FIGHTER_FX = Color(200, 230, 255, 180)
    }

    override fun onHit(
        projectile: DamagingProjectileAPI,
        target: CombatEntityAPI,
        point: Vector2f,
        shieldHit: Boolean,
        damageResult: ApplyDamageResultAPI,
        engine: CombatEngineAPI,
    ) {
        val baseDamage = projectile.damageAmount

        when (target) {
            is MissileAPI -> {
                // 对导弹：额外伤害
                val bonusDamage = baseDamage * (MISSILE_MULT - 1f)
                engine.applyDamage(
                    target, point, bonusDamage,
                    projectile.damageType, 0f,
                    false, false, projectile.source
                )
                engine.addHitParticle(point, Vector2f(), 20f, 1f, 0.08f, MISSILE_FX)
            }

            is ShipAPI -> {
                if (target.isFighter) {
                    // 对战机：额外伤害
                    val bonusDamage = baseDamage * (FIGHTER_MULT - 1f)
                    engine.applyDamage(
                        target, point, bonusDamage,
                        projectile.damageType, 0f,
                        false, false, projectile.source
                    )
                    engine.addHitParticle(point, Vector2f(), 25f, 0.9f, 0.1f, FIGHTER_FX)
                }
                // 对舰体伤害降低在 weapon_data.csv 中通过较低基础伤害实现
            }
        }
    }
}
