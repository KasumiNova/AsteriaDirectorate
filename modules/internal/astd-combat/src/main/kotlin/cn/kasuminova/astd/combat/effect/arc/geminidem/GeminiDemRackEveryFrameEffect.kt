package cn.kasuminova.astd.combat.effect.arc.geminidem

import cn.kasuminova.astd.renderer.effect.system.GeminiDemRackVisuals
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin
import com.fs.starfarer.api.combat.WeaponAPI

class GeminiDemRackEveryFrameEffect : EveryFrameWeaponEffectPlugin {

    override fun advance(amount: Float, engine: CombatEngineAPI, weapon: WeaponAPI) {
        if (engine.isPaused) return
        lockTriggerWhenSalvoIncomplete(weapon)
        GeminiDemRackVisuals.onWeaponFrame(engine, weapon)
    }

    companion object {
        private const val SALVO_AMMO_COST = 2

        private const val FIRE_LOCK_COOLDOWN = 0.05f

        // 原版 setRemainingCooldownTo 在非 CHARGING_DOWN 态会重置 BurstFireTracker，
        // 连发窗口内垫冷却会掐掉第二发 dummy，连发进行中必须让行。
        private fun lockTriggerWhenSalvoIncomplete(weapon: WeaponAPI) {
            if (weapon.isInBurst) return
            if (weapon.ammo >= SALVO_AMMO_COST) return
            if (weapon.cooldownRemaining >= FIRE_LOCK_COOLDOWN) return
            weapon.setRemainingCooldownTo(FIRE_LOCK_COOLDOWN)
        }
    }
}
