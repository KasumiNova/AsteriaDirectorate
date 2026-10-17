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
        lockTriggerWhenSalvoIncomplete(weapon)
        GeminiDemRackVisuals.onWeaponFrame(engine, weapon)
    }

    companion object {
        /**
         * 一次完整齐射消耗的 dummy 弹药数（双管各一发，与 [GeminiDemSalvoOnFireEffect] 的双弹头齐射对应）。
         * 弹药低于此值时开火只会打出残缺齐射（onFire 的回声发去重依赖完整双发），必须锁扳机。
         */
        private const val SALVO_AMMO_COST = 2

        /**
         * 锁扳机用的冷却垫（秒）：弹药回充到够齐射后最多再等这么一拍即可开火，玩家几乎无感。
         * 取值须小于 weapon_data 的 burst delay（0.1s）：垫的冷却若越过连发窗口，
         * 会在 burst 间隙把武器重新压住，干扰下一轮连发的起射节奏。
         */
        private const val FIRE_LOCK_COOLDOWN = 0.05f

        /**
         * 剩余弹药不足一次完整齐射时压住扳机（每帧把冷却垫到 [FIRE_LOCK_COOLDOWN]）。
         *
         * 连发进行中必须让行（[WeaponAPI.isInBurst] 守卫，2026-10 实机判例）：
         * 首发 dummy 击发后弹药即短暂低于 [SALVO_AMMO_COST]，若此时垫冷却，
         * 原版 `setRemainingCooldownTo` 在非 CHARGING_DOWN 态（burst 窗口恰处于 ACTIVE 态）
         * 会直接 reset BurstFireTracker 掐掉第二发 dummy——实机表现为
         * 「2 备弹打一轮齐射只扣 1 发、双弹头照常生成」。
         */
        private fun lockTriggerWhenSalvoIncomplete(weapon: WeaponAPI) {
            if (weapon.isInBurst) return
            if (weapon.ammo >= SALVO_AMMO_COST) return
            if (weapon.cooldownRemaining >= FIRE_LOCK_COOLDOWN) return
            weapon.setRemainingCooldownTo(FIRE_LOCK_COOLDOWN)
        }
    }
}
