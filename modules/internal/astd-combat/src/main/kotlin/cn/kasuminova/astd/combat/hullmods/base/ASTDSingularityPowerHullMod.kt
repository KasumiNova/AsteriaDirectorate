package cn.kasuminova.astd.combat.hullmods.base

import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.ui.dsl.HullmodTheme
import cn.kasuminova.astd.ui.dsl.HullmodThemes
import cn.kasuminova.astd.ui.dsl.HullmodTone
import cn.kasuminova.astd.ui.dsl.v
import cn.kasuminova.astd.ui.dsl.hl
import cn.kasuminova.astd.ui.dsl.hullmodCard
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.impl.campaign.ids.Stats
import com.fs.starfarer.api.ui.TooltipMakerAPI
import java.awt.Color

class ASTDSingularityPowerHullMod : BaseHullMod() {

    override fun applyEffectsBeforeShipCreation(hullSize: ShipAPI.HullSize, stats: MutableShipStatsAPI, id: String) {
        stats.dynamic.getStat(Stats.CORONA_EFFECT_MULT).modifyMult(id, ENVIRONMENT_EFFECT_MULT)
        val variant = stats.variant
        if (variant != null) {
            val config = ASTDDualModeRegistry.configForVariant(variant)
            if (config != null) variant.ensureDualModeSwitcherPresent(config)
        }
    }

    override fun advanceInCombat(ship: ShipAPI, amount: Float) {
        val values = Values.resolve(ship.owner == 0)
        val stats = ship.mutableStats
        stats.ventRateMult.modifyMult(MOD_ID, values.ventRateMult)
        stats.overloadTimeMod.modifyMult(MOD_ID, values.overloadTimeMult)
        stats.ballisticWeaponFluxCostMod.modifyMult(MOD_ID, values.weaponFluxCostMult)
        stats.energyWeaponFluxCostMod.modifyMult(MOD_ID, values.weaponFluxCostMult)
        stats.missileWeaponFluxCostMod.modifyMult(MOD_ID, values.weaponFluxCostMult)
    }

    override fun addPostDescriptionSection(
        tooltip: TooltipMakerAPI,
        hullSize: ShipAPI.HullSize,
        ship: ShipAPI?,
        width: Float,
        isForModSpec: Boolean,
    ) {
        val values = Values.resolve(ship == null || ship.owner == 0)
        tooltip.hullmodCard(width, theme(), spec?.displayName) {
            para(descKey())
            heading("ui.hullmod.export.section.effect")
            table {
                row(
                    "ui.hullmod.singularity_power.attr.vent_rate",
                    "ui.hullmod.singularity_power.value.vent_rate",
                    labelTone = HullmodTone.DEFAULT,
                    valueTone = HullmodTone.HIGHLIGHT,
                    v("pct", signedPercentOf(values.ventRateMult - 1f)),
                )
                row(
                    "ui.hullmod.singularity_power.attr.overload",
                    "ui.hullmod.singularity_power.value.overload",
                    labelTone = HullmodTone.DEFAULT,
                    valueTone = HullmodTone.HIGHLIGHT,
                    v("pct", signedPercentOf(values.overloadTimeMult - 1f)),
                )
                row(
                    "ui.hullmod.singularity_power.attr.environment",
                    "ui.hullmod.singularity_power.value.environment",
                    labelTone = HullmodTone.DEFAULT,
                    valueTone = HullmodTone.HIGHLIGHT,
                )
                row(
                    "ui.hullmod.singularity_power.attr.weapon_flux",
                    "ui.hullmod.singularity_power.value.weapon_flux",
                    labelTone = HullmodTone.DEFAULT,
                    valueTone = HullmodTone.HIGHLIGHT,
                    v("pct", signedPercentOf(values.weaponFluxCostMult - 1f)),
                )
            }
            spacer(6f)
            para("ui.hullmod.export.difficulty_note", hl("难度系数", HullmodTone.DEFAULT))
        }
    }

    override fun showInRefitScreenModPickerFor(ship: ShipAPI): Boolean = false

    override fun getBorderColor(): Color = theme().borderColor

    override fun getNameColor(): Color = theme().nameColor

    private fun theme(): HullmodTheme =
        if (spec?.id == ID_LENS) THEME_LENS else THEME_ARC

    private fun descKey(): String =
        if (spec?.id == ID_LENS) "ui.hullmod.lens_singularity_power.desc" else "ui.hullmod.arc_singularity_power.desc"

    internal data class Values(
        val ventRateMult: Float,
        val overloadTimeMult: Float,
        val weaponFluxCostMult: Float,
    ) {
        companion object {
            fun resolve(isPlayer: Boolean): Values = Values(
                ventRateMult = DifficultyTuningImpl.valueFor(VENT_RATE, isPlayer),
                overloadTimeMult = DifficultyTuningImpl.valueFor(OVERLOAD_TIME, isPlayer),
                weaponFluxCostMult = DifficultyTuningImpl.valueFor(WEAPON_FLUX_COST, isPlayer),
            )
        }
    }

    companion object {
        const val ID_ARC: String = "astd_arc_singularity_power"

        const val ID_LENS: String = "astd_lens_singularity_power"

        private const val MOD_ID = "astd_singularity_power"

        val VENT_RATE = ScalingEntry(1.10f, 1.20f, 1.50f)

        val OVERLOAD_TIME = ScalingEntry(0.90f, 0.80f, 0.50f)

        const val ENVIRONMENT_EFFECT_MULT: Float = 0f

        val WEAPON_FLUX_COST = ScalingEntry(0.95f, 0.90f, 0.75f)

        private val THEME_ARC = HullmodThemes.ARC

        private val THEME_LENS = HullmodThemes.LENS
    }
}
