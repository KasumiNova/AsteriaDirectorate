package cn.kasuminova.astd.combat.effect.lens.signature.singularity

import cn.kasuminova.astd.combat.effect.generic.projectile.ProjectileTracerManager
import cn.kasuminova.astd.combat.effect.generic.projectile.ProjectileVisual
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import org.lwjgl.util.vector.Vector2f

/**
 * 奇点导弹生命周期监听：
 * - 记录是否进入过 isFading（用于区分“自然超时淡出” vs “被点防打爆/强制移除”）
 * - 在 PROJECTILE_REMOVED 时，若【未命中】则触发“自爆”（视觉 + 伤害）。
 */
internal class SingularityShotDownDetonationVisual(
    private val engine: CombatEngineAPI,
    private val variant: SingularityDetonationFx.Variant,
) : ProjectileVisual {

    private var everFading = false
    private var hasHit = false
    private var detonated = false

    private var fadeStarted = false
    private var fadeOutSeconds = 0.10f
    private var fadeTimer = 0f

    private var lastLoc = Vector2f(0f, 0f)
    private var lastVel = Vector2f(0f, 0f)
    private var lastProjectile: DamagingProjectileAPI? = null

    override fun advance(projectile: DamagingProjectileAPI, amount: Float) {
        try {
            lastProjectile = projectile
            lastLoc = Vector2f(projectile.location)
            lastVel = projectile.velocity?.let { Vector2f(it) } ?: Vector2f(0f, 0f)
            everFading = everFading || projectile.isFading
            hasHit = hasHit || (projectile.customData[SingularityKeys.MISSILE_HAS_HIT] == true)
        } catch (_: Throwable) {
        }

        if (fadeStarted && amount > 0f) {
            fadeTimer += amount
        }
    }

    override fun beginFadeOut(reason: ProjectileTracerManager.FadeReason, fadeOutSeconds: Float) {
        // 注意：同一个弹体会先收到 PROJECTILE_FADING，再收到 PROJECTILE_REMOVED。
        if (!fadeStarted) {
            fadeStarted = true
            this.fadeOutSeconds = fadeOutSeconds.coerceAtLeast(0.01f)
            fadeTimer = 0f
        }

        if (detonated) return
        if (reason != ProjectileTracerManager.FadeReason.PROJECTILE_REMOVED) return

        // 命中后不再触发“自爆”（避免与 OnHitEffect 重复）。
        // 需求：击落与淡出都需要造成伤害，因此不再排除 everFading。
        if (hasHit) {
            detonated = true
            return
        }

        detonated = true
        try {
            val p = lastProjectile
            if (p != null) {
                val mode = if (everFading) {
                    SingularityDetonationFx.DetonationMode.FADE_OUT
                } else {
                    SingularityDetonationFx.DetonationMode.SHOT_DOWN
                }
                SingularityDetonationFx.detonateWithDamage(engine, p, lastLoc, lastVel, variant, mode)
            } else {
                // 理论上不会发生（移除前 advance 应该至少跑过一次）；兜底仅播视觉。
                SingularityDetonationFx.spawn(engine, lastLoc, lastVel, variant)
            }
        } catch (_: Throwable) {
        }
    }

    override fun isFadeOutOver(): Boolean {
        return fadeStarted && fadeTimer >= fadeOutSeconds
    }

    override fun delete() {
        // 纯触发器，无需回收资源。
    }
}
