package cn.kasuminova.astd.combat.hullmods.arc

import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.renderer.effect.system.Xc002GhostWingsEffect
import cn.kasuminova.astd.ui.dsl.HullmodThemes
import cn.kasuminova.astd.ui.dsl.HullmodTone
import cn.kasuminova.astd.ui.dsl.hl
import cn.kasuminova.astd.ui.dsl.hullmodCard
import cn.kasuminova.astd.ui.dsl.v
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI
import java.awt.Color

class ASTDImaginaryWingsHullMod : BaseHullMod() {

    override fun advanceInCombat(ship: ShipAPI, amount: Float) {
        if (!ship.isAlive || ship.isHulk) return
        if (ship.hullSpec?.hullId != HULL_ID && ship.hullSpec?.baseHullId != HULL_ID) return
        val engine = Global.getCombatEngine() ?: return
        Xc002GhostWingsEffect.track(engine, ship)
        if (engine.isPaused) return

        val isPlayer = ship.owner == 0
        val pool = ImaginaryCharge.of(ship)
        pool.add(DifficultyTuningImpl.valueFor(ImaginaryWingsTuning.CHARGE_REGEN, isPlayer) * amount)
        applyGlideStats(ship, isPlayer)
        applyZeroFluxThreshold(ship)
        exchangeSystemCharge(ship, pool)
    }

    private fun applyGlideStats(ship: ShipAPI, isPlayer: Boolean) {
        val stats = ship.mutableStats
        val baseMaxSpeed = stats.maxSpeed.baseValue
        val ratio = if (baseMaxSpeed > 1f) (ship.velocity.length() / baseMaxSpeed).coerceIn(0f, 1f) else 0f
        val rofBonus = DifficultyTuningImpl.valueFor(ImaginaryWingsTuning.GLIDE_ROF_PERCENT, isPlayer) * ratio
        val glideMult = 1f + rofBonus / 100f
        stats.ballisticRoFMult.modifyMult(MOD_ID_GLIDE, glideMult)
        stats.energyRoFMult.modifyMult(MOD_ID_GLIDE, glideMult)
        stats.ballisticAmmoRegenMult.modifyMult(MOD_ID_GLIDE, glideMult)
        stats.energyAmmoRegenMult.modifyMult(MOD_ID_GLIDE, glideMult)
    }

    private fun applyZeroFluxThreshold(ship: ShipAPI) {
        ship.mutableStats.zeroFluxMinimumFluxLevel.modifyFlat(MOD_ID_FLUX, ImaginaryWingsTuning.ZERO_FLUX_THRESHOLD)
    }

    private fun exchangeSystemCharge(ship: ShipAPI, pool: ImaginaryCharge) {
        val system = ship.system ?: return
        val ammo = system.ammo
        if (!pool.tryExchangeSystemCharge(ammo)) return
        system.setAmmo(ammo + 1)
    }

    override fun addPostDescriptionSection(
        tooltip: TooltipMakerAPI,
        hullSize: ShipAPI.HullSize,
        ship: ShipAPI?,
        width: Float,
        isForModSpec: Boolean,
    ) {
        val isPlayer = ship == null || ship.owner == 0
        val regen = DifficultyTuningImpl.valueFor(ImaginaryWingsTuning.CHARGE_REGEN, isPlayer).toInt()
        val glideRof = DifficultyTuningImpl.valueFor(ImaginaryWingsTuning.GLIDE_ROF_PERCENT, isPlayer).toInt()
        val zeroFluxThreshold = (ImaginaryWingsTuning.ZERO_FLUX_THRESHOLD * 100f).toInt()
        val exchangeThreshold = ImaginaryWingsTuning.EXCHANGE_THRESHOLD.toInt()
        val exchangeCost = ImaginaryWingsTuning.EXCHANGE_COST.toInt()
        tooltip.hullmodCard(width, THEME, spec?.displayName) {
            para("ui.hullmod.imaginary_wings.desc", hl("\"灵能\"", Color(219, 40, 40, 255)))
            heading("ui.hullmod.imaginary_wings.section.charge")
            para(
                "ui.hullmod.imaginary_wings.line.charge",
                v("regen", regen),
                hl("$regen%", THEME.nameColor),
            )
            heading("ui.hullmod.imaginary_wings.section.glide")
            para(
                "ui.hullmod.imaginary_wings.line.glide.threshold",
                v("threshold", zeroFluxThreshold),
                hl("$zeroFluxThreshold%", HullmodTone.POSITIVE),
            )
            para(
                "ui.hullmod.imaginary_wings.line.glide.rof",
                v("rof", glideRof),
                hl("$glideRof%", HullmodTone.POSITIVE),
            )
            heading("ui.hullmod.imaginary_wings.section.exchange")
            para(
                "ui.hullmod.imaginary_wings.line.exchange",
                v("count", 0),
                v("threshold", exchangeThreshold),
                v("cost", exchangeCost),
                v("gain", 1),
                hl("0", HullmodTone.HIGHLIGHT),
                hl("$exchangeThreshold%", THEME.nameColor),
                hl("$exchangeCost%", THEME.nameColor),
                hl("1", HullmodTone.HIGHLIGHT),
            )
            spacer(6f)
            para("ui.hullmod.export.difficulty_note", hl("难度系数", HullmodTone.DEFAULT))
        }
    }

    override fun isApplicableToShip(ship: ShipAPI): Boolean {
        val hullId = ship.hullSpec?.hullId
        val baseHullId = ship.hullSpec?.baseHullId
        return hullId == HULL_ID || baseHullId == HULL_ID
    }

    override fun showInRefitScreenModPickerFor(ship: ShipAPI): Boolean = false

    override fun getBorderColor(): Color = THEME.borderColor

    override fun getNameColor(): Color = THEME.nameColor

    companion object {
        private const val HULL_ID = "astd_xc_002"

        private const val MOD_ID_GLIDE = "astd_imaginary_wings_glide"

        private const val MOD_ID_FLUX = "astd_imaginary_wings_flux"

        private val THEME = HullmodThemes.IMAGINARY
    }
}
