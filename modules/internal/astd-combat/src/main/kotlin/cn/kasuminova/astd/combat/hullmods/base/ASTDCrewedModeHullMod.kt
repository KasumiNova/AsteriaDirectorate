package cn.kasuminova.astd.combat.hullmods.base

import cn.kasuminova.astd.ui.dsl.HullmodThemes
import cn.kasuminova.astd.ui.dsl.hullmodCard
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI
import java.awt.Color

class ASTDCrewedModeHullMod : BaseHullMod() {

    companion object {
        private val THEME = HullmodThemes.CREWED
    }

    override fun applyEffectsBeforeShipCreation(hullSize: ShipAPI.HullSize, stats: MutableShipStatsAPI, id: String) {
        val variant = stats.variant ?: return
        if (!variant.isASTDShipVariant()) return
        val config = ASTDDualModeRegistry.configForVariant(variant) ?: return
        if (id != config.crewedModeId) return

        if (!variant.hasHullMod(config.switcherId)) {
            variant.activateDualMode(config, config.automatedModeId, stats)
            variant.addMod(config.switcherId)
            stats?.fleetMember?.let { ASTDDualModeMirror.record(it, config.automatedModeId) }
            return
        }

        config.crewedSystemId?.let { variant.hullSpec?.shipSystemId = it }
    }

    override fun isApplicableToShip(ship: ShipAPI): Boolean = ship.isASTDShip()

    override fun showInRefitScreenModPickerFor(ship: ShipAPI): Boolean = false

    override fun addPostDescriptionSection(
        tooltip: TooltipMakerAPI,
        hullSize: ShipAPI.HullSize,
        ship: ShipAPI?,
        width: Float,
        isForModSpec: Boolean
    ) {
        tooltip.hullmodCard(width, THEME, spec?.displayName) {
            para("ui.hullmod.mode_crewed.summary")
            heading("ui.hullmod.export.section.mode")
            para("ui.hullmod.mode_crewed.hint")
        }
    }

    override fun getBorderColor(): Color = THEME.borderColor

    override fun getNameColor(): Color = THEME.nameColor
}
