package cn.kasuminova.astd.combat.effect.arc.geminidem

import cn.kasuminova.astd.renderer.effect.system.GeminiDemRackVisuals
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin
import com.fs.starfarer.api.combat.WeaponAPI

/**
 * 双子星 DEM 发射架的 everyFrameEffect：每帧驱动 [GeminiDemRackVisuals]
 * （逐管弹体染色 + 红/蓝光效叠加的登记与渲染安装）。
 */
class GeminiDemRackEveryFrameEffect : EveryFrameWeaponEffectPlugin {

    override fun advance(amount: Float, engine: CombatEngineAPI, weapon: WeaponAPI) {
        if (engine.isPaused) return
        GeminiDemRackVisuals.onWeaponFrame(engine, weapon)
    }
}
