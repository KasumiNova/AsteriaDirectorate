package cn.kasuminova.astd.combat.hullmods.arc

import cn.kasuminova.astd.renderer.effect.hullmods.ASTDNegentropyEdgeVfx
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.OnHitEffectPlugin
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI
import org.lwjgl.util.vector.Vector2f

/** 追击虚粒子命中效果：让真实导弹实体碰撞结算伤害，并补充 BoxUtil 命中光束。 */
class ASTDPursuitVirtualParticleOnHitEffect : OnHitEffectPlugin {

    override fun onHit(
        projectile: DamagingProjectileAPI,
        target: CombatEntityAPI,
        point: Vector2f,
        shieldHit: Boolean,
        damageResult: ApplyDamageResultAPI,
        engine: CombatEngineAPI,
    ) {
        ASTDNegentropyEdgeVfx.spawnCollapseStrike(engine, projectile.location, point, 0.45f)
    }
}