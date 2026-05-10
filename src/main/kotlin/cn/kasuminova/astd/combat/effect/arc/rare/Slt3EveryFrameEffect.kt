package cn.kasuminova.astd.combat.effect.arc.rare

import cn.kasuminova.astd.combat.effect.generic.CombatVfxBootstrap
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin
import com.fs.starfarer.api.combat.WeaponAPI
import java.awt.Color
import java.util.WeakHashMap

/** SLT-3：连射结束后的短时回冲惩罚。 */
class Slt3EveryFrameEffect : EveryFrameWeaponEffectPlugin {

    private data class State(
        var lastAmmo: Int = -1,
        var recoilTimer: Float = 0f,
    )

    private companion object {
        private val STATE = WeakHashMap<WeaponAPI, State>()
    }

    override fun advance(amount: Float, engine: CombatEngineAPI, weapon: WeaponAPI) {
        if (engine.isPaused) return

        // 兜底：确保战斗内 VFX dispatcher 已安装。
        CombatVfxBootstrap.ensureInstalled(engine)

        val ship = weapon.ship ?: return
        if (!ship.isAlive || ship.isHulk) return

        val id = "astd_slt3_recoil_${weapon.slot?.id ?: weapon.hashCode()}"

        val st = STATE.getOrPut(weapon) { State() }

        val ammo = weapon.ammo
        val maxAmmo = weapon.maxAmmo

        // 不使用弹药的情况下不处理
        if (maxAmmo <= 0 || !weapon.usesAmmo()) {
            ship.mutableStats.weaponTurnRateBonus.unmodify(id)
            return
        }

        // 弹药打空：连射窗口结束（回装由原生弹匣系统处理）
        val justEmptied = st.lastAmmo > 0 && ammo == 0
        if (justEmptied) {
            st.recoilTimer = 1.0f
        }
        st.lastAmmo = ammo

        if (st.recoilTimer > 0f) {
            st.recoilTimer -= amount
            ship.mutableStats.weaponTurnRateBonus.modifyMult(id, 0.80f)
            ship.setJitterUnder(id, Color(200, 240, 255, 75), 0.45f, 4, 6f)
        } else {
            ship.mutableStats.weaponTurnRateBonus.unmodify(id)
        }
    }
}
