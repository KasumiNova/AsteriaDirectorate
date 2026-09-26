package cn.kasuminova.astd.combat.effect.lens.iceshard

import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.OnHitEffectPlugin
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI
import org.lwjgl.util.vector.Vector2f

/**
 * 源生冰晶子射弹的命中回调薄入口：挂冰晶 `.proj` 的 `onHitEffect`。
 *
 * 职责：引擎暂停跳过 → 命中点回退 → 仅「命中舰船装甲/船体」（非护盾、非 hulk、存活）时
 * 注册 [IceShardAttachScript] 附着冻结（周期伤害 / 15su 增伤 / 星云特效全部在附着脚本内）。
 * 命中护盾/导弹/陨石等其余目标无任何附加效果（面板破片伤害已由引擎原生结算）。
 */
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
