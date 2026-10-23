package cn.kasuminova.astd.combat.hullmods.lens

import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.ui.dsl.HullmodThemes
import cn.kasuminova.astd.ui.dsl.HullmodTone
import cn.kasuminova.astd.ui.dsl.hl
import cn.kasuminova.astd.ui.dsl.hullmodCard
import cn.kasuminova.astd.ui.dsl.v
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.FighterLaunchBayAPI
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.impl.campaign.ids.Stats
import com.fs.starfarer.api.ui.TooltipMakerAPI
import java.awt.Color

class ASTDDimensionalFoldingDeckHullMod : BaseHullMod() {

    override fun applyEffectsBeforeShipCreation(
        hullSize: ShipAPI.HullSize,
        stats: MutableShipStatsAPI,
        id: String,
    ) {
        stats.dynamic.getMod(Stats.FIGHTER_COST_MOD).modifyMult(id, FoldingDeckTuning.OP_COST_MULT)
        stats.dynamic.getMod(Stats.BOMBER_COST_MOD).modifyMult(id, FoldingDeckTuning.OP_COST_MULT)
        stats.dynamic.getMod(Stats.INTERCEPTOR_COST_MOD).modifyMult(id, FoldingDeckTuning.OP_COST_MULT)
        stats.dynamic.getMod(Stats.SUPPORT_COST_MOD).modifyMult(id, FoldingDeckTuning.OP_COST_MULT)
    }

    // 原版仅对 affectsOPCosts() 返回 true 的船插结算装配 OP。
    override fun affectsOPCosts(): Boolean = true

    override fun advanceInCombat(ship: ShipAPI, amount: Float) {
        val engine = Global.getCombatEngine() ?: return
        if (engine.isPaused || amount <= 0f || ship.isHulk) return
        if (!ship.isZw102Ship()) return

        val bonusMult = FoldingDeckTuning.resolveWingSizeMult(DifficultyTuningImpl, ship.owner == 0)
        ship.mutableStats.fighterRefitTimeMult.modifyMult(
            MOD_ID, FoldingDeckTuning.refitTimeMult(DifficultyTuningImpl, ship.owner == 0),
        )
        val canRefill = !ship.fluxTracker.isOverloadedOrVenting && ship.currentCR > 0f

        for (bay in ship.launchBaysCopy) {
            val wing = bay.wing ?: continue
            val spec = wing.spec
            if (spec == null) {
                log.warn("dimensional_folding_deck: bay wing spec is null on ${ship.hullSpec?.hullId}, skipped")
                continue
            }
            val baseNum = spec.numFighters
            val limit = FoldingDeckTuning.wingSizeLimit(baseNum, bonusMult)

            if (bay.extraDeploymentLimit != limit) {
                bay.extraDeploymentLimit = limit
                bay.extraDuration = FoldingDeckTuning.EXTRA_FIGHTER_DURATION
            }
            if (canRefill) {
                val extraNeeded = limit - baseNum
                if (bay.extraDeployments < extraNeeded) bay.extraDeployments = extraNeeded
                val fillKey = initialFillKey(ship, bay)
                if (engine.customData[fillKey] != true) {
                    if (wing.wingMembers.count { !it.isHulk } >= limit) {
                        engine.customData[fillKey] = true
                    } else {
                        bay.makeCurrentIntervalFast()
                    }
                }
            }
        }
    }

    override fun addPostDescriptionSection(
        tooltip: TooltipMakerAPI,
        hullSize: ShipAPI.HullSize,
        ship: ShipAPI?,
        width: Float,
        isForModSpec: Boolean,
    ) {
        val isPlayer = ship == null || ship.owner == 0
        val wingSizePct = "+${(FoldingDeckTuning.resolveWingSizeMult(DifficultyTuningImpl, isPlayer) * 100f).toInt()}%"
        val refitPct = "+${(DifficultyTuningImpl.valueFor(FoldingDeckTuning.REFIT_RATE_BONUS, isPlayer) * 100f).toInt()}%"
        tooltip.hullmodCard(width, THEME, spec?.displayName) {
            para("ui.hullmod.dimensional_folding_deck.desc")
            heading("ui.hullmod.export.section.effect")
            table {
                row(
                    "ui.hullmod.dimensional_folding_deck.attr.op_cost",
                    "ui.hullmod.dimensional_folding_deck.value.op_cost",
                    HullmodTone.DEFAULT, HullmodTone.RED,
                )
                row(
                    "ui.hullmod.dimensional_folding_deck.attr.wing_size",
                    "ui.hullmod.dimensional_folding_deck.value.wing_size",
                    HullmodTone.DEFAULT, HullmodTone.HIGHLIGHT,
                    v("pct", wingSizePct),
                )
                row(
                    "ui.hullmod.dimensional_folding_deck.attr.refit_rate",
                    "ui.hullmod.dimensional_folding_deck.value.refit_rate",
                    HullmodTone.DEFAULT, HullmodTone.HIGHLIGHT,
                    v("pct", refitPct),
                )
            }
            spacer(6f)
            para("ui.hullmod.export.difficulty_note", hl("难度系数", HullmodTone.DEFAULT))
        }
    }

    override fun isApplicableToShip(ship: ShipAPI): Boolean = ship.isZw102Ship()

    override fun showInRefitScreenModPickerFor(ship: ShipAPI): Boolean = false

    override fun getBorderColor(): Color = THEME.borderColor

    override fun getNameColor(): Color = THEME.nameColor

    companion object {
        const val MOD_ID: String = "astd_dimensional_folding_deck"

        const val HULL_ID: String = "astd_zw_102"

        private val log = Global.getLogger(ASTDDimensionalFoldingDeckHullMod::class.java)

        private fun initialFillKey(ship: ShipAPI, bay: FighterLaunchBayAPI): String =
            "astd_dfd_initial_fill:${ship.id}:${bay.weaponSlot?.id}"

        private val THEME = HullmodThemes.LENS
    }
}

internal fun ShipAPI?.isZw102Ship(): Boolean {
    val s = this ?: return false
    val hullId = s.hullSpec?.hullId
    val baseHullId = s.hullSpec?.baseHullId
    return hullId == ASTDDimensionalFoldingDeckHullMod.HULL_ID ||
            baseHullId == ASTDDimensionalFoldingDeckHullMod.HULL_ID
}
