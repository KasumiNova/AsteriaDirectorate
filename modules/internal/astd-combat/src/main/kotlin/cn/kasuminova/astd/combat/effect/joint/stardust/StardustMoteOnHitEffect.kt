package cn.kasuminova.astd.combat.effect.joint.stardust

import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.OnHitEffectPlugin
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI
import org.lwjgl.util.vector.Vector2f
import java.awt.Color

class StardustMoteOnHitEffect : OnHitEffectPlugin {

    override fun onHit(
        projectile: DamagingProjectileAPI,
        target: CombatEntityAPI,
        point: Vector2f,
        shieldHit: Boolean,
        damageResult: ApplyDamageResultAPI,
        engine: CombatEngineAPI
    ) {
        val source = projectile.source
        val values = StardustMoteTuning.resolve(DifficultyTuningImpl, isPlayer = source?.owner == 0)
        val base = projectile.damageAmount
        val projId = projectile.projectileSpecId
        if (projId == null) {
            StardustMoteTuning.warnOnce("nullProjId") { "星尘光尘命中结算收到 null projectileSpecId（配置异常），按 ARC 线配色兜底" }
        }
        val color = StardustMoteTuning.colorForProj(projId ?: StardustMoteIds.PROJ_ARC)

        when {
            target is MissileAPI -> {
                engine.applyDamage(
                    projectile, target, point, base * values.antiMissileBonus,
                    DamageType.ENERGY, 0f, false, false, source, true,
                )
            }

            target is ShipAPI && target.isFighter -> {
                engine.applyDamage(
                    projectile, target, point, base * values.antiFighterBonus,
                    DamageType.ENERGY, 0f, false, false, source, true,
                )
            }

            target is ShipAPI -> {
                var pierceChance = 1f
                pierceChance *= target.mutableStats.dynamic.getValue("shield_pierced_mult", 1f)
                if (shieldHit) {
                    pierceChance *= SHIELD_PIERCE_CHANCE
                }
                if (!shieldHit || Math.random() < pierceChance) {
                    engine.spawnEmpArcPierceShields(
                        source,
                        point,
                        target,
                        target,
                        DamageType.ENERGY,
                        0f,
                        base * values.shipEmpMult,
                        100000f,
                        IMPACT_ARC_SOUND,
                        20f,
                        color,
                        Color(255, 255, 255, 255),
                    )
                }
            }
        }

        Global.getSoundPlayer().playSound(IMPACT_SOUND, 1f, 1f, point, Vector2f())
    }

    companion object {
        private const val IMPACT_SOUND = "mote_attractor_impact_normal"
        private const val IMPACT_ARC_SOUND = "mote_attractor_impact_emp_arc"

        private const val SHIELD_PIERCE_CHANCE = 0.5f
    }
}
