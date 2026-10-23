package cn.kasuminova.astd.combat.effect.generic.gravitycollapse

import com.fs.starfarer.api.combat.BeamAPI
import com.fs.starfarer.api.combat.BeamEffectPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import java.awt.Color

open class HiddenBeamRenderEffect : BeamEffectPlugin {
    override fun advance(amount: Float, engine: CombatEngineAPI, beam: BeamAPI) {
        // 修改 beam.from/to 会同时改变碰撞与伤害。
        try {
            beam.coreColor = Color(0, 0, 0, 0)
        } catch (_: Throwable) {
        }
        try {
            beam.fringeColor = Color(0, 0, 0, 0)
        } catch (_: Throwable) {
        }
        try {
            beam.width = 0.01f
        } catch (_: Throwable) {
        }
        try {
            beam.hitGlow = null
        } catch (_: Throwable) {
        }
    }
}
