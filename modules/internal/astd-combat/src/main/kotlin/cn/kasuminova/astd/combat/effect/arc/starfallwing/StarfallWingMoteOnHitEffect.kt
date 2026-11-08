package cn.kasuminova.astd.combat.effect.arc.starfallwing

import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.OnHitEffectPlugin
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI
import org.lwjgl.util.vector.Vector2f

/** 星羽光矢命中护盾时附加 1 层 [StarfallWingErosionStacks] 振频蚀穿。 */
class StarfallWingMoteOnHitEffect : OnHitEffectPlugin {

    override fun onHit(
        projectile: DamagingProjectileAPI,
        target: CombatEntityAPI?,
        point: Vector2f,
        shieldHit: Boolean,
        impact: ApplyDamageResultAPI,
        engine: CombatEngineAPI,
    ) {
        if (!shieldHit) return
        val ship = target as? ShipAPI ?: return
        StarfallWingErosionStacks.attachLayer(ship, engine, projectile.source)
    }
}
