package cn.kasuminova.astd.combat.effect.arc.starfallwing

import cn.kasuminova.astd.combat.hullmods.arc.ImaginaryCharge
import cn.kasuminova.astd.combat.hullmods.arc.ImaginaryWingsTuning
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.OnHitEffectPlugin
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI
import org.lwjgl.util.vector.Vector2f

/**
 * 坠星残翼普通光梭命中敌舰：恢复 [虚数充能] 并登记主炮最近命中目标。
 * 伤害与命中判定由原版碰撞链路完成，本插件只处理与充能池的联动。
 */
class StarfallWingShotOnHitEffect : OnHitEffectPlugin {

    override fun onHit(
        projectile: DamagingProjectileAPI,
        target: CombatEntityAPI?,
        point: Vector2f,
        shieldHit: Boolean,
        impact: ApplyDamageResultAPI,
        engine: CombatEngineAPI,
    ) {
        val ship = target as? ShipAPI ?: return
        if (ship.isFighter || !ship.isAlive || ship.isHulk) return
        val source = projectile.source ?: return
        if (ship.owner == source.owner) return
        val pool = ImaginaryCharge.of(source)
        pool.tryHitGain(ImaginaryWingsTuning.HIT_GAIN)
        pool.lastMainGunHit = ship
    }
}
