package cn.kasuminova.astd.combat.hullmods.arc

import cn.kasuminova.astd.api.combat.CombatFeedback
import cn.kasuminova.astd.impl.combat.CombatFeedbackImpl
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.internal.i18n.I18n
import cn.kasuminova.astd.renderer.effect.system.Xc002GhostWingsEffect
import cn.kasuminova.astd.ui.dsl.HullmodThemes
import cn.kasuminova.astd.ui.dsl.HullmodTone
import cn.kasuminova.astd.ui.dsl.v
import cn.kasuminova.astd.ui.dsl.hl
import cn.kasuminova.astd.ui.dsl.hullmodCard
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipSystemAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI
import java.awt.Color

class ASTDImaginaryWingsHullMod : BaseHullMod() {

    override fun advanceInCombat(ship: ShipAPI, amount: Float) {
        if (!ship.isAlive || ship.isHulk) return
        val engine = Global.getCombatEngine() ?: return
        Xc002GhostWingsEffect.track(engine, ship)
        if (engine.isPaused) return

        val isPlayer = ship.owner == 0
        val peakSpeedPercent = DifficultyTuningImpl.valueFor(ImaginaryWingsTuning.SPEED_BOOST_PERCENT, isPlayer)
        val fullSpeedPercent = DifficultyTuningImpl.valueFor(ImaginaryWingsTuning.FULL_SPEED_BOOST_PERCENT, isPlayer)
        val overCapPerPercent = DifficultyTuningImpl.valueFor(ImaginaryWingsTuning.OVER_CAP_DAMAGE_PER_PERCENT, isPlayer)

        val systemActive = ship.system?.state == ShipSystemAPI.SystemState.ACTIVE
        val wasActive = ship.customData[KEY_PREV_ACTIVE] == true
        if (systemActive && !wasActive) {
            ship.customData[KEY_WINDOW_START] = engine.getTotalElapsedTime(false)
        }
        ship.customData[KEY_PREV_ACTIVE] = systemActive

        val windowStart = ship.customData[KEY_WINDOW_START] as? Float
        val elapsed = if (windowStart != null) engine.getTotalElapsedTime(false) - windowStart else Float.MAX_VALUE
        val speedBonus = ImaginaryWingsTuning.speedBonusPercent(elapsed, peakSpeedPercent)
        if (speedBonus > 0f) {
            ship.mutableStats.maxSpeed.modifyPercent(MOD_ID, speedBonus)
            ship.mutableStats.acceleration.modifyPercent(MOD_ID, speedBonus)
            ship.mutableStats.deceleration.modifyPercent(MOD_ID, speedBonus)
            ship.mutableStats.maxTurnRate.modifyPercent(MOD_ID, speedBonus)
            ship.mutableStats.turnAcceleration.modifyPercent(MOD_ID, speedBonus)
        } else {
            ship.mutableStats.maxSpeed.unmodifyPercent(MOD_ID)
            ship.mutableStats.acceleration.unmodifyPercent(MOD_ID)
            ship.mutableStats.deceleration.unmodifyPercent(MOD_ID)
            ship.mutableStats.maxTurnRate.unmodifyPercent(MOD_ID)
            ship.mutableStats.turnAcceleration.unmodifyPercent(MOD_ID)
        }

        val baseMaxSpeed = ship.mutableStats.maxSpeed.baseValue
        val ratio = if (baseMaxSpeed > 1f) ship.velocity.length() / baseMaxSpeed else 0f

        val sustainMult = ImaginaryWingsTuning.sustainedMult(ratio, fullSpeedPercent)
        ship.mutableStats.ballisticAmmoRegenMult.modifyMult(MOD_ID, sustainMult)
        ship.mutableStats.energyAmmoRegenMult.modifyMult(MOD_ID, sustainMult)
        ship.mutableStats.missileAmmoRegenMult.modifyMult(MOD_ID, sustainMult)
        ship.mutableStats.ballisticRoFMult.modifyMult(MOD_ID, sustainMult)
        ship.mutableStats.energyRoFMult.modifyMult(MOD_ID, sustainMult)

        val damageMult = ImaginaryWingsTuning.overCapDamageMult(ratio, overCapPerPercent)
        ship.mutableStats.ballisticWeaponDamageMult.modifyMult(MOD_ID, damageMult)
        ship.mutableStats.energyWeaponDamageMult.modifyMult(MOD_ID, damageMult)
        ship.mutableStats.missileWeaponDamageMult.modifyMult(MOD_ID, damageMult)

        if (speedBonus > 0f && ship == engine.playerShip) {
            feedback.maintainPlayerStatus(
                engine, HUD_KEY, HUD_ICON,
                I18n[I18n.Categories.MOD, "ui.imaginary_wings.status.title"],
                I18n.t(
                    I18n.Categories.MOD, "ui.imaginary_wings.status.desc",
                    "bonus" to formatPercent(speedBonus),
                    "sustain" to formatPercent((sustainMult - 1f) * 100f, signed = true),
                    "damage" to formatPercent((damageMult - 1f) * 100f, signed = true),
                ),
                negative = false,
            )
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
        val peakSpeedPercent = DifficultyTuningImpl.valueFor(ImaginaryWingsTuning.SPEED_BOOST_PERCENT, isPlayer)
        val fullSpeedPercent = DifficultyTuningImpl.valueFor(ImaginaryWingsTuning.FULL_SPEED_BOOST_PERCENT, isPlayer)
        val overCapPerPercent = DifficultyTuningImpl.valueFor(ImaginaryWingsTuning.OVER_CAP_DAMAGE_PER_PERCENT, isPlayer)
        val restPercent = ImaginaryWingsTuning.restBoostPercent(fullSpeedPercent)
        tooltip.hullmodCard(width, THEME, spec?.displayName) {
            para("ui.hullmod.imaginary_wings.desc", hl("\"灵能\"", Color(219, 40, 40, 255)))
            heading("ui.hullmod.imaginary_wings.section.overload")
            para(
                "ui.hullmod.imaginary_wings.line.overload",
                v("secs", ImaginaryWingsTuning.WINDOW_SECONDS.toInt()),
                v("pct", formatPercent(peakSpeedPercent) + "%"),
                hl(ImaginaryWingsTuning.WINDOW_SECONDS.toInt().toString(), HullmodTone.HIGHLIGHT),
                hl(formatPercent(peakSpeedPercent) + "%", HullmodTone.POSITIVE)
            )
            heading("ui.hullmod.imaginary_wings.section.glide")
            para(
                "ui.hullmod.imaginary_wings.line.glide",
                v("rest", formatPercent(kotlin.math.abs(restPercent)) + "%"),
                v("full", formatPercent(fullSpeedPercent) + "%"),
                hl(formatPercent(kotlin.math.abs(restPercent)) + "%", if (restPercent < 0f) HullmodTone.RED else HullmodTone.POSITIVE),
                hl(formatPercent(fullSpeedPercent) + "%", HullmodTone.POSITIVE)
            )
            para(
                "ui.hullmod.imaginary_wings.line.glide_overcap",
                v("pct", formatPercent(overCapPerPercent) + "%"),
                hl("1%", HullmodTone.HIGHLIGHT), hl(formatPercent(overCapPerPercent) + "%", HullmodTone.POSITIVE)
            )
            spacer(6f)
            para("ui.hullmod.export.difficulty_note", hl("难度系数", HullmodTone.DEFAULT))
        }
    }

    override fun showInRefitScreenModPickerFor(ship: ShipAPI): Boolean = false

    override fun isApplicableToShip(ship: ShipAPI): Boolean {
        val hullId = ship.hullSpec?.hullId
        val baseHullId = ship.hullSpec?.baseHullId
        return hullId == HULL_ID || baseHullId == HULL_ID
    }

    override fun getBorderColor(): Color = THEME.borderColor

    override fun getNameColor(): Color = THEME.nameColor

    companion object {
        private const val HULL_ID = "astd_xc_002"

        private const val MOD_ID = "astd_imaginary_wings"

        private const val KEY_PREV_ACTIVE = "astd_imaginary_wings_prev_active"

        private const val KEY_WINDOW_START = "astd_imaginary_wings_window_start"

        private const val HUD_KEY = "astd_imaginary_wings_status"
        private const val HUD_ICON = "graphics/hullmods/astd_imaginary_wings.png"

        private val feedback: CombatFeedback = CombatFeedbackImpl

        private val THEME = HullmodThemes.IMAGINARY

        private fun formatPercent(value: Float, signed: Boolean = false): String {
            val rounded = Math.round(value * 10f) / 10f
            val body = if (rounded == Math.floor(rounded.toDouble()).toFloat()) {
                rounded.toInt().toString()
            } else {
                rounded.toString()
            }
            return if (signed && value > 0f) "+$body" else body
        }
    }
}
