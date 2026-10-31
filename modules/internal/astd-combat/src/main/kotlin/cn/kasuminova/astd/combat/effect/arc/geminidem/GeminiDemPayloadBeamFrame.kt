package cn.kasuminova.astd.combat.effect.arc.geminidem

import cn.kasuminova.astd.renderer.effect.projectile.beam.BeamLineUtil
import cn.kasuminova.astd.renderer.effect.projectile.beam.BeamVisualClipper
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.WeaponAPI
import java.util.IdentityHashMap

// payload 自绘束体（GeminiDemPayloadBeamVfx）与伤害帧结算（GeminiDemPayloadBeamEffect）共用的端点来源与存活口径。
internal object GeminiDemPayloadBeamFrame {

    // 原版伤害 tick 约 4Hz（间隔约 0.25s），命中宽限须大于 tick 间隔。
    const val DAMAGE_GRACE_SECONDS = 0.3f

    private const val CACHE_KEY = "astd_gemini_dem_payload_beam_endpoints"

    private class FrameCache {
        var frameTime = Float.NaN
        val lines = IdentityHashMap<WeaponAPI, BeamLineUtil.BeamLine>()
    }

    // 同一帧内两个插件的调用时机不同（期间舰船位置可能推进），按帧缓存保证电弧起点与束体端点取同一结果。
    fun endpoint(engine: CombatEngineAPI, weapon: WeaponAPI): BeamLineUtil.BeamLine {
        val cache = engine.customData.getOrPut(CACHE_KEY) { FrameCache() } as FrameCache
        val now = engine.getTotalElapsedTime(false)
        if (cache.frameTime != now) {
            cache.frameTime = now
            cache.lines.clear()
        }
        return cache.lines.getOrPut(weapon) {
            BeamVisualClipper.resolve(engine, weapon, phasedShipsBlock = false)
        }
    }

    fun isDamageLive(firingVisible: Boolean, damagingThisFrame: Boolean, sinceLastDamage: Float): Boolean =
        firingVisible || damagingThisFrame || sinceLastDamage <= DAMAGE_GRACE_SECONDS
}
