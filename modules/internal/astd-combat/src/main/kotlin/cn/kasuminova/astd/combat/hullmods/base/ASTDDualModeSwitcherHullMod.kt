package cn.kasuminova.astd.combat.hullmods.base

import cn.kasuminova.astd.internal.i18n.I18n
import cn.kasuminova.astd.ui.dsl.HullmodThemes
import cn.kasuminova.astd.ui.dsl.hullmodCard
import cn.kasuminova.astd.ui.dsl.v
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI
import java.awt.Color

class ASTDDualModeSwitcherHullMod : BaseHullMod() {

    companion object {
        private val THEME = HullmodThemes.CREWED
    }

    override fun applyEffectsBeforeShipCreation(hullSize: ShipAPI.HullSize, stats: MutableShipStatsAPI, id: String) {
        val variant = stats.variant ?: return
        val config = ASTDDualModeRegistry.configForVariant(variant) ?: return
        variant.ensureASTDDualModeState(config, stats)
        ASTDHullmodSprites.swap(spec, ASTDHullmodSprites.switcherSpriteFor(variant, config))
    }

    override fun isApplicableToShip(ship: ShipAPI): Boolean = ship.isASTDShip()

    override fun addPostDescriptionSection(
        tooltip: TooltipMakerAPI,
        hullSize: ShipAPI.HullSize,
        ship: ShipAPI?,
        width: Float,
        isForModSpec: Boolean,
    ) {
        val config = ASTDDualModeRegistry.configForShip(ship)

        tooltip.hullmodCard(width, THEME, spec?.displayName) {
            spacer(2f)
            para("ui.hullmod.dual_mode_switcher.summary", 4f)

            if (config != null) {
                val automated = isAutomatedMode(ship, config)
                val currentModeName = modeName(automated)
                val targetModeName = modeName(!automated)

                spacer(4f)
                para("ui.hullmod.dual_mode_switcher.current", 2f, v("mode", currentModeName))
                para("ui.hullmod.dual_mode_switcher.hint", 2f, v("target", targetModeName))
            }
        }
    }

    private fun isAutomatedMode(ship: ShipAPI?, config: ASTDDualModeConfig): Boolean {
        val variant = try {
            ship?.variant
        } catch (_: Throwable) {
            null
        } ?: return false
        val permaMods = try {
            variant.permaMods
        } catch (_: Throwable) {
            null
        } ?: return false
        if (permaMods.contains(config.automatedModeId)) return true
        if (permaMods.contains(config.crewedModeId)) return false
        return false
    }

    private fun modeName(automated: Boolean): String =
        if (automated) I18n[I18n.Categories.MOD, "ui.dual_mode.automated"]
        else I18n[I18n.Categories.MOD, "ui.dual_mode.crewed"]

    override fun getBorderColor(): Color = THEME.borderColor

    override fun getNameColor(): Color = THEME.nameColor
}
