package cn.kasuminova.astd.combat.effect.arc.rare

import cn.kasuminova.astd.combat.effect.generic.CombatVfxBootstrap
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin
import com.fs.starfarer.api.combat.WeaponAPI
import java.awt.Color
import java.util.WeakHashMap
import kotlin.math.max
import kotlin.math.min

/**
 * DRV-11：换弹/真空期自惩罚（稳定性下降）。
 *
 * 说明：引擎并没有“只影响某一门炮”的稳定性 stat，所以这里用 ship 的 weaponTurnRateBonus 做一个轻量的全局惩罚。
 * 对 MVP 来说够用；后续如果要更精细，可以换成更贴合的 stat 或者改用武器脚本侧抖动。
 */
class Drv11EveryFrameEffect : EveryFrameWeaponEffectPlugin {

    private data class State(
        var lastAmmo: Int = -1,
        var heat: Float = 0f,
    )

    private companion object {
        private const val RELOAD_TIME = 6.0f
        private val STATE = WeakHashMap<WeaponAPI, State>()

        private val GLOW = Color(120, 210, 255, 255)
        private val VACUUM_JITTER = Color(120, 200, 255, 70)
    }

    override fun advance(amount: Float, engine: CombatEngineAPI, weapon: WeaponAPI) {
        if (engine.isPaused) return

        // 兜底：确保战斗内 VFX dispatcher 已安装。
        CombatVfxBootstrap.ensureInstalled(engine)

        val ship = weapon.ship ?: return
        if (!ship.isAlive || ship.isHulk) return

        val id = "astd_drv11_stability_${weapon.slot?.id ?: weapon.hashCode()}"

        val st = STATE.getOrPut(weapon) { State() }

        val ammo = weapon.ammo
        val maxAmmo = weapon.maxAmmo

        // 不使用弹药的情况下不处理（避免误伤其它占位武器）
        if (maxAmmo <= 0 || !weapon.usesAmmo()) {
            ship.mutableStats.weaponTurnRateBonus.unmodify(id)
            weapon.setGlowAmount(0f, GLOW)
            return
        }

        // 重要：弹体 VFX（曳光/炮口粒子）由 weaponSpec 的通用 onFireEffect
        // `ProjectileSpecOnFireDispatcher` 按 projectileSpecId（.proj id）分发。

        // 弹匣打空：进入原生回装阶段（用 ammo/sec + reload size 控制回装节奏）
        val justEmptied = st.lastAmmo > 0 && ammo == 0
        st.lastAmmo = ammo
        if (justEmptied) {
            st.heat = 0f
            ship.setJitterUnder(id, VACUUM_JITTER, 0.55f, 6, 8f)
        }

        if (maxAmmo > 0 && ammo == 0) {
            // 回装期：稳定性惩罚 + 真空/散热提示（不再手动补弹/禁火）
            ship.mutableStats.weaponTurnRateBonus.modifyMult(id, 0.85f)
            ship.setJitterUnder(id, VACUUM_JITTER, 0.35f, 3, 4f)
            weapon.setGlowAmount(0f, GLOW)
            return
        }

        // 弹匣期：轻量“炮口辉光逐步升高”（按持续开火热量）
        st.heat = if (weapon.isFiring) {
            min(1f, st.heat + amount * 0.9f)
        } else {
            max(0f, st.heat - amount * 0.6f)
        }
        weapon.setGlowAmount(st.heat, GLOW)

        ship.mutableStats.weaponTurnRateBonus.unmodify(id)
    }
}
