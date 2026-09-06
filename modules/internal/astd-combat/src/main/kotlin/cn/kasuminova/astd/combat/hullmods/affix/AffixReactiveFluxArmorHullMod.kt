package cn.kasuminova.astd.combat.hullmods.affix

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.combat.affix.AffixRegistry
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamageAPI
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.listeners.AdvanceableListener
import com.fs.starfarer.api.combat.listeners.DamageTakenModifier
import org.lwjgl.util.vector.Vector2f

/**
 * 词缀 M-10：反应式辐能装甲（[AffixRegistry.ID_REACTIVE_FLUX_ARMOR]）。
 * 按难度系数：降低 75%~90% 强制排辐期间受到的装甲与船体伤害（最终乘区）；
 * 强制排辐速率降低 35%（固定值）。
 *
 * 排辐状态判定 + 伤害修正由 [VentGuardListener] 承担；电网深化升级复用同一监听器。
 */
class AffixReactiveFluxArmorHullMod : BaseHullMod() {

    companion object {
        val REDUCTION = ScalingEntry(v1 = 0.75f, v2 = 0.825f, v5 = 0.90f)

        /** 排辐速率惩罚（固定 -35%）。 */
        const val VENT_RATE_MULT = 0.65f

        fun damageTakenMult(tuning: DifficultyTuning): Float = 1f - tuning.value(REDUCTION)
    }

    override fun applyEffectsBeforeShipCreation(hullSize: ShipAPI.HullSize, stats: MutableShipStatsAPI, id: String) {
        stats.ventRateMult.modifyMult(id, VENT_RATE_MULT)
    }

    override fun applyEffectsAfterShipCreation(ship: ShipAPI, id: String) {
        if (!ship.hasListenerOfClass(VentGuardListener::class.java)) {
            ship.addListener(VentGuardListener(ship, damageTakenMult(AffixShared.tuning)))
        }
    }

    /**
     * 排辐减伤监听器：仅在强制排辐期间、且非护盾命中时削减伤害。
     *
     * @property damageTakenMult 伤害乘区（1 - 减免比例），由安装时按难度系数定值
     */
    class VentGuardListener(
        private val ship: ShipAPI,
        private val damageTakenMult: Float,
    ) : DamageTakenModifier, AdvanceableListener {

        override fun advance(amount: Float) {
            if (!ship.isAlive || ship.isHulk) {
                ship.removeListener(this)
            }
        }

        override fun modifyDamageTaken(param: Any?, target: CombatEntityAPI?, damage: DamageAPI?, point: Vector2f?, shieldHit: Boolean): String? {
            if (target !== ship || damage == null || shieldHit) return null
            if (!ship.fluxTracker.isVenting) return null
            damage.modifier.modifyMult(STAT_ID, damageTakenMult)
            return null
        }

        companion object {
            const val STAT_ID: String = "astd_affix_reactive_flux_armor"
        }
    }
}
