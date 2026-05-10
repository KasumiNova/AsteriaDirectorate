package cn.kasuminova.astd.combat.effect.arc.signature.stellarjet

import cn.kasuminova.astd.renderer.boxutil.BoxUtilCombatVfx
import cn.kasuminova.astd.combat.effect.generic.projectile.ProjectileVfxPresets

import com.fs.starfarer.api.combat.BeamAPI
import com.fs.starfarer.api.combat.BeamEffectPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import java.awt.Color

/**
 * 恒星喷射（喷射口）beamEffect：保持为空实现。
 *
 * 说明：
 * - 伤害/命中完全交给原版 beam 机制；这里不做任何额外 debuff/电弧等逻辑。
 * - 该字段在很多原版 beam 武器中都会配置；这里显式提供一个 no-op，
 *   避免引用 Graviton/Tachyon 等自带特殊效果的 beamEffect。
 */
class StellarJetBeamEffect : BeamEffectPlugin {
    override fun advance(amount: Float, engine: CombatEngineAPI, beam: BeamAPI) {
        // 隐藏原版 beam 渲染（但保留其伤害/命中结算）。
        // 注意：不要在这里改 beam.from/to（那会影响碰撞/伤害）；只改“怎么画”。
        try {
            beam.setCoreColor(Color(0, 0, 0, 0))
        } catch (_: Throwable) {
        }
        try {
            beam.setFringeColor(Color(0, 0, 0, 0))
        } catch (_: Throwable) {
        }
        // 极细宽度 + 透明色：双保险，避免某些材质/后处理仍残留亮线
        try {
            beam.setWidth(0.01f)
        } catch (_: Throwable) {
        }
        // 移除命中端 glow（否则即便束体不可见也可能看到“亮点”）
        try {
            beam.setHitGlow(null)
        } catch (_: Throwable) {
        }
    }
}
