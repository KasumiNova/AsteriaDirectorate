package cn.kasuminova.astd.combat.effect.lens.production

import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.OnHitEffectPlugin
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import kotlin.math.min

/**
 * SGL-8 协同制导蜂群：命中护盾叠加扰动层（封顶），降低命中/制导稳定性。
 */
class Sgl8SwarmDisturbanceOnHitEffect : OnHitEffectPlugin {

    companion object {
        /** 最大层数 */
        private const val MAX_STACKS = 3

        /** 每层武器散布增加 */
        private const val SPREAD_PER_STACK = 0.08f

        /** 每层导弹制导降低 */
        private const val GUIDANCE_PENALTY_PER_STACK = 0.08f

        /** 层持续时间 */
        private const val STACK_DURATION = 2.0f

        /** 特效颜色 */
        private val FX_COLOR = Color(160, 180, 220, 140)
    }

    override fun onHit(
        projectile: DamagingProjectileAPI,
        target: CombatEntityAPI,
        point: Vector2f,
        shieldHit: Boolean,
        damageResult: ApplyDamageResultAPI,
        engine: CombatEngineAPI,
    ) {
        // 仅对护盾命中生效
        if (!shieldHit) return
        val ship = target as? ShipAPI ?: return
        if (ship.isHulk) return

        val time = engine.getTotalElapsedTime(false)
        val targetKey = System.identityHashCode(ship)
        val stackKey = "sgl8_stacks:$targetKey"
        val timerKey = "sgl8_timer:$targetKey"

        // 获取当前层数
        var stacks = engine.customData[stackKey] as? Int ?: 0

        // 增加层数（封顶）
        stacks = min(MAX_STACKS, stacks + 1)
        engine.customData[stackKey] = stacks
        engine.customData[timerKey] = time + STACK_DURATION

        // 应用效果
        applyDisturbance(ship, stacks, engine, time)

        // 视觉效果
        engine.addHitParticle(point, Vector2f(), 18f, 0.8f, 0.12f, FX_COLOR)
    }

    private fun applyDisturbance(ship: ShipAPI, stacks: Int, engine: CombatEngineAPI, startTime: Float) {
        val id = "sgl8_disturb_${System.identityHashCode(ship)}"
        val targetKey = System.identityHashCode(ship)

        // 增加武器散布
        val spreadMult = 1f + (SPREAD_PER_STACK * stacks)
        ship.mutableStats.maxRecoilMult.modifyMult(id, spreadMult)
        ship.mutableStats.recoilPerShotMult.modifyMult(id, spreadMult)

        // 降低导弹制导
        val guidanceMult = 1f - (GUIDANCE_PENALTY_PER_STACK * stacks)
        ship.mutableStats.missileGuidance.modifyMult(id, guidanceMult)

        // 注册/更新清理器
        val cleanupKey = "sgl8_cleanup:$targetKey"
        if (engine.customData[cleanupKey] != true) {
            engine.customData[cleanupKey] = true
            engine.addPlugin(object : com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin() {
                override fun advance(amount: Float, events: MutableList<com.fs.starfarer.api.input.InputEventAPI>?) {
                    if (engine.isPaused) return
                    val currentTime = engine.getTotalElapsedTime(false)
                    val expiryTime = engine.customData["sgl8_timer:$targetKey"] as? Float ?: 0f

                    if (currentTime >= expiryTime) {
                        ship.mutableStats.maxRecoilMult.unmodify(id)
                        ship.mutableStats.recoilPerShotMult.unmodify(id)
                        ship.mutableStats.missileGuidance.unmodify(id)
                        engine.customData["sgl8_stacks:$targetKey"] = null
                        engine.customData["sgl8_timer:$targetKey"] = null
                        engine.customData[cleanupKey] = null
                        engine.removePlugin(this)
                    }
                }
            })
        }
    }
}
