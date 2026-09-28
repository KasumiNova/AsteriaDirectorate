package cn.kasuminova.astd.combat.effect.arc.starfallwing

import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.OnHitEffectPlugin
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI
import org.lwjgl.util.vector.Vector2f

/**
 * 坠星残翼子射弹的原版命中钩子（挂 mote spec 的 `onHitEffect`）。
 *
 * 子射弹碰撞已回归原版导弹口径（collisionClass=MISSILE_NO_FF，伤害/阻挡/消散全走原版），
 * 本钩子只承担机制附加面：命中护盾（[shieldHit]=true）时对目标附加
 * [StarfallWingTuning.MOTE_STACKS_ON_SHIELD] 层「振频适应」；命中船体不附加。
 * 伤害量由 spawn 时写入的 damageAmount（主弹面板 ×20%）经原版结算，脚本不再触碰。
 */
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
        StarfallWingAdaptationStacks.attachStacks(
            ship, engine, StarfallWingTuning.MOTE_STACKS_ON_SHIELD, projectile.source,
        )
    }
}
