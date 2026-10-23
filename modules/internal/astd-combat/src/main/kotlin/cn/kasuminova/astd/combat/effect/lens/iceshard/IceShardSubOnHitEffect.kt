package cn.kasuminova.astd.combat.effect.lens.iceshard

import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.OnHitEffectPlugin
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI
import org.lwjgl.util.vector.Vector2f

class IceShardSubOnHitEffect : OnHitEffectPlugin {

    override fun onHit(
        projectile: DamagingProjectileAPI,
        target: CombatEntityAPI,
        point: Vector2f?,
        shieldHit: Boolean,
        damageResult: ApplyDamageResultAPI,
        engine: CombatEngineAPI,
    ) {
        if (engine.isPaused) return
        if (shieldHit) return
        val ship = target as? ShipAPI ?: return
        if (ship.isHulk || !ship.isAlive) return
        val missile = projectile as? MissileAPI ?: return
        val hitPoint = point ?: projectile.location ?: return

        val owner = projectile.owner
        engine.addPlugin(
            IceShardAttachScript(
                ship = ship,
                shard = missile,
                hitPoint = Vector2f(hitPoint),
                shardDamage = projectile.damageAmount,
                source = projectile.source,
                dotRatio = IceShardMirvDifficulty.resolve(IceShardMirvDifficulty.DOT_RATIO, owner),
                amp = IceShardMirvDifficulty.resolve(IceShardMirvDifficulty.AMP, owner),
            ),
        )
        engine.customData[IceShardAttachScript.TELEMETRY_ATTACHES] =
            (engine.customData[IceShardAttachScript.TELEMETRY_ATTACHES] as? Int ?: 0) + 1
    }
}
