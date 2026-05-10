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
 * JMB-2 相干干扰束：持续命中叠层，降低命中稳定性/制导质量（封顶）。
 *
 * 注意：此效果设计用于光束武器的 onHit，但也可用于投射物。
 * 对护盾内目标效果衰减。
 */
class Jmb2CoherenceJammingOnHitEffect : OnHitEffectPlugin {

    companion object {
        /** 最大层数 */
        private const val MAX_STACKS = 4

        /** 叠层间隔（秒）*/
        private const val STACK_INTERVAL = 0.7f

        /** 层衰减时间（秒）*/
        private const val DECAY_TIME = 2.5f

        /** 每层命中稳定性降低 */
        private const val ACCURACY_PENALTY_PER_STACK = 0.06f

        /** 每层导弹制导降低 */
        private const val GUIDANCE_PENALTY_PER_STACK = 0.06f

        /** 对护盾内目标效果衰减 */
        private const val SHIELD_EFFECTIVENESS = 0.5f

        /** 特效颜色 */
        private val FX_COLOR = Color(180, 160, 220, 120)
    }

    override fun onHit(
        projectile: DamagingProjectileAPI,
        target: CombatEntityAPI,
        point: Vector2f,
        shieldHit: Boolean,
        damageResult: ApplyDamageResultAPI,
        engine: CombatEngineAPI,
    ) {
        val ship = target as? ShipAPI ?: return
        if (ship.isHulk) return

        val time = engine.getTotalElapsedTime(false)
        val targetKey = System.identityHashCode(ship)

        val stackKey = "jmb2_stacks:$targetKey"
        val lastHitKey = "jmb2_last:$targetKey"
        val decayKey = "jmb2_decay:$targetKey"

        // 检查叠层间隔
        val lastHit = engine.customData[lastHitKey] as? Float ?: 0f
        if (time - lastHit < STACK_INTERVAL) {
            // 刷新衰减计时器
            engine.customData[decayKey] = time + DECAY_TIME
            return
        }

        // 更新命中时间
        engine.customData[lastHitKey] = time
        engine.customData[decayKey] = time + DECAY_TIME

        // 增加层数
        var stacks = engine.customData[stackKey] as? Int ?: 0
        stacks = min(MAX_STACKS, stacks + 1)
        engine.customData[stackKey] = stacks

        // 应用效果（对护盾衰减）
        val effectiveness = if (shieldHit) SHIELD_EFFECTIVENESS else 1f
        applyJamming(ship, stacks, effectiveness, engine, targetKey)

        // 视觉效果
        engine.addHitParticle(point, Vector2f(), 22f, 0.7f, 0.15f, FX_COLOR)
    }

    private fun applyJamming(ship: ShipAPI, stacks: Int, effectiveness: Float, engine: CombatEngineAPI, targetKey: Int) {
        val id = "jmb2_jam_$targetKey"

        val effectiveStacks = stacks * effectiveness

        // 降低武器精度
        val accuracyMult = 1f + (ACCURACY_PENALTY_PER_STACK * effectiveStacks)
        ship.mutableStats.maxRecoilMult.modifyMult(id, accuracyMult)
        ship.mutableStats.recoilPerShotMult.modifyMult(id, accuracyMult)

        // 降低导弹制导
        val guidanceMult = 1f - (GUIDANCE_PENALTY_PER_STACK * effectiveStacks)
        ship.mutableStats.missileGuidance.modifyMult(id, guidanceMult)

        // 注册/更新清理器
        val cleanupKey = "jmb2_cleanup:$targetKey"
        if (engine.customData[cleanupKey] != true) {
            engine.customData[cleanupKey] = true
            engine.addPlugin(object : com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin() {
                override fun advance(amount: Float, events: MutableList<com.fs.starfarer.api.input.InputEventAPI>?) {
                    if (engine.isPaused) return
                    val currentTime = engine.getTotalElapsedTime(false)
                    val expiryTime = engine.customData["jmb2_decay:$targetKey"] as? Float ?: 0f

                    if (currentTime >= expiryTime) {
                        ship.mutableStats.maxRecoilMult.unmodify(id)
                        ship.mutableStats.recoilPerShotMult.unmodify(id)
                        ship.mutableStats.missileGuidance.unmodify(id)
                        engine.customData["jmb2_stacks:$targetKey"] = null
                        engine.customData["jmb2_last:$targetKey"] = null
                        engine.customData["jmb2_decay:$targetKey"] = null
                        engine.customData[cleanupKey] = null
                        engine.removePlugin(this)
                    }
                }
            })
        }
    }
}
