package cn.kasuminova.astd.combat.hullmods.arc

import cn.kasuminova.astd.combat.hullmods.base.ASTDHullModTooltipRenderer
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI
import java.awt.Color

class ASTDArcFlareDualModeSwitcherHullMod : BaseHullMod() {

    companion object {
        private val THEME = ASTDHullModTooltipRenderer.Theme(
            nameColor = Color(255, 214, 118),
            borderColor = Color(255, 168, 58),
            headerBackground = Color(76, 48, 10, 185),
            sectionBackground = Color(52, 34, 8, 120),
            accentColor = Color(180, 140, 50),
        )
    }

    override fun applyEffectsBeforeShipCreation(hullSize: ShipAPI.HullSize, stats: MutableShipStatsAPI, id: String) {
        val variant = stats.variant ?: return
        if (!variant.isASTDShipVariant()) return
        variant.ensureASTDArcFlareModeState(stats)
    }

    override fun isApplicableToShip(ship: ShipAPI): Boolean = ship.isASTDShip()

    override fun addPostDescriptionSection(tooltip: TooltipMakerAPI, hullSize: ShipAPI.HullSize, ship: ShipAPI?, width: Float, isForModSpec: Boolean) {
        ASTDHullModTooltipRenderer.renderBlocks(
            tooltip = tooltip,
            width = width,
            title = spec?.displayName ?: "",
            theme = THEME,
            blocks = listOf(
                ASTDHullModTooltipRenderer.paragraph("ui.hullmod.switcher.summary"),
                ASTDHullModTooltipRenderer.paragraph("ui.hullmod.switcher.line.1"),
                ASTDHullModTooltipRenderer.paragraph("ui.hullmod.switcher.line.2"),
            ),
        )
    }

    override fun showInRefitScreenModPickerFor(ship: ShipAPI): Boolean = ship.isASTDShip()

    override fun getBorderColor(): Color = THEME.borderColor

    override fun getNameColor(): Color = THEME.nameColor
}
