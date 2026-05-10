package cn.kasuminova.astd.combat.shipsystems

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript
import com.fs.starfarer.api.plugins.ShipSystemStatsScript
import cn.kasuminova.astd.internal.i18n.I18n

/**
 * astd_stellar_jet（恒星喷射）
 *
 * 设计案要点：
 * - 系统激活期间，不强制关盾；但护盾进入“旁路整形”状态：
 *   - 盾弧收束到正前方
 *   - 维持与受击幅能压力显著增加
 *
 * 伤害/推进等“喷射本体”逻辑由签名武器实现（见 weapons/signature/StellarJetEmitterEveryFrameEffect）。
 */
class StellarJetSystemStats : BaseShipSystemScript() {

    companion object {
        /** 盾弧最小值（越小越“收束到正前方”）。 */
        private const val MIN_SHIELD_ARC_DEG = 60f

        /** 护盾维持成本倍率（最大）。 */
        private const val SHIELD_UPKEEP_MULT_MAX = 2.25f

        /** 护盾受击幅能压力倍率（最大）。 */
        private const val SHIELD_DAMAGE_TAKEN_MULT_MAX = 1.85f

        /** 喷射口：光束 DPS = 最大辐能 × 15%（数值展示用）。 */
        private const val EMITTER_BEAM_DPS_MULT = 0.15f

        /** 喷射口：耗辐能 = 最大辐能 × 20%/秒（数值展示用）。 */
        private const val EMITTER_FLUX_PER_SEC_MULT = 0.20f

        /** 喷射口：单次激活预算倍率 = 150%（数值展示用）。 */
        private const val EMITTER_BUDGET_MULT = 1.50f

        private fun pct(mult: Float): Int = (mult * 100f + 0.5f).toInt()

        private fun shipKey(ship: ShipAPI): Int = System.identityHashCode(ship)

        private fun key(ship: ShipAPI, suffix: String): String = "astd_stellar_jet:$suffix:${shipKey(ship)}"
    }

    override fun apply(stats: MutableShipStatsAPI, id: String, state: ShipSystemStatsScript.State, effectLevel: Float) {
        val ship = stats.entity as? ShipAPI ?: return
        if (ship.isHulk) return

        val level = effectLevel.coerceIn(0f, 1f)
        val fluxLevel = ship.fluxTracker.fluxLevel.coerceIn(0f, 1f)
        // 整形强度：既受系统 effectLevel 影响，也随“高幅能”而更明显
        val shaping = (level * fluxLevel).coerceIn(0f, 1f)

        // 提高护盾压力：维持+受击
        stats.shieldUpkeepMult.modifyMult(id, lerp(1f, SHIELD_UPKEEP_MULT_MAX, shaping))
        stats.shieldDamageTakenMult.modifyMult(id, lerp(1f, SHIELD_DAMAGE_TAKEN_MULT_MAX, shaping))

        val shield = ship.shield ?: return
        val engine = Global.getCombatEngine() ?: return

        // 保存原始盾弧，只保存一次（避免叠加系统/其他 buff 时每帧抖动）
        val arcKey = key(ship, "origArc")
        val activeArcKey = key(ship, "origActiveArc")
        if (engine.customData[arcKey] == null) {
            engine.customData[arcKey] = shield.arc
        }
        if (engine.customData[activeArcKey] == null) {
            engine.customData[activeArcKey] = shield.activeArc
        }

        val origArc = (engine.customData[arcKey] as? Float) ?: shield.arc
        val desiredArc = lerp(origArc, MIN_SHIELD_ARC_DEG.coerceAtMost(origArc), shaping)

        // “旁路整形”：收束到正前方
        try {
            shield.setActiveArc(desiredArc)
            shield.setArc(desiredArc)
            shield.forceFacing(ship.facing)
        } catch (_: Throwable) {
            // 有些特殊盾/单位可能不支持完整操作；保持 stats 侧的压力增益即可
        }
    }

    override fun unapply(stats: MutableShipStatsAPI, id: String) {
        stats.shieldUpkeepMult.unmodify(id)
        stats.shieldDamageTakenMult.unmodify(id)

        val ship = stats.entity as? ShipAPI ?: return
        val shield = ship.shield ?: return
        val engine = Global.getCombatEngine() ?: return

        val arcKey = key(ship, "origArc")
        val activeArcKey = key(ship, "origActiveArc")

        val origArc = engine.customData[arcKey] as? Float
        val origActiveArc = engine.customData[activeArcKey] as? Float

        if (origArc != null || origActiveArc != null) {
            try {
                if (origActiveArc != null) shield.setActiveArc(origActiveArc)
                if (origArc != null) shield.setArc(origArc)
            } catch (_: Throwable) {
                // ignore
            }
        }

        engine.customData.remove(arcKey)
        engine.customData.remove(activeArcKey)
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    override fun getStatusData(index: Int, state: ShipSystemStatsScript.State, effectLevel: Float): ShipSystemStatsScript.StatusData? {
        if (index == 0) return ShipSystemStatsScript.StatusData(I18n[I18n.Categories.MOD, "system.stellar_jet.status.0"], false)
        if (index == 1) return ShipSystemStatsScript.StatusData(I18n[I18n.Categories.MOD, "system.stellar_jet.status.1"], true)
        if (index == 2) {
            val dpsPct = pct(EMITTER_BEAM_DPS_MULT)
            val fluxPct = pct(EMITTER_FLUX_PER_SEC_MULT)
            val budgetPct = pct(EMITTER_BUDGET_MULT)
            return ShipSystemStatsScript.StatusData(
                I18n.t(
                    I18n.Categories.MOD,
                    "system.stellar_jet.status.2",
                    "dpsPct" to dpsPct,
                    "fluxPct" to fluxPct,
                    "budgetPct" to budgetPct,
                ),
                false,
            )
        }
        return null
    }
}
