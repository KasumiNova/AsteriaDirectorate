package cn.kasuminova.astd.combat.effect.arc.signature.stasisfield

import com.fs.starfarer.api.combat.BeamAPI
import com.fs.starfarer.api.combat.BeamEffectPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import java.awt.Color

/**
 * 通用：隐藏原版 beam 渲染（保留伤害/命中结算），
 * 由 everyFrameEffect（自绘 BoxUtil trail + 环特效）接管视觉表现。
 */
open class HiddenBeamRenderEffect : BeamEffectPlugin {
    override fun advance(amount: Float, engine: CombatEngineAPI, beam: BeamAPI) {
        // 注意：不要改 beam.from/to（那会影响碰撞/伤害）；只改“怎么画”。
        try {
            beam.setCoreColor(Color(0, 0, 0, 0))
        } catch (_: Throwable) {
        }
        try {
            beam.setFringeColor(Color(0, 0, 0, 0))
        } catch (_: Throwable) {
        }
        try {
            beam.setWidth(0.01f)
        } catch (_: Throwable) {
        }
        try {
            beam.setHitGlow(null)
        } catch (_: Throwable) {
        }
    }
}
