package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.combat.hullmods.arc.ASTDArcProductionShipIds
import cn.kasuminova.astd.combat.hullmods.arc.ASTDArcProductionVfx
import cn.kasuminova.astd.combat.hullmods.arc.ASTDArcCombatUtil
import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript
import com.fs.starfarer.api.plugins.ShipSystemStatsScript
import java.awt.Color
import kotlin.math.roundToInt

class ASTDPlasmaArmorShieldBoostSystemStats : BaseShipSystemScript() {

    companion object {
        private const val RAMP_SECONDS = 2f
        private const val SHIELD_DR_MAX = 0.50f
        private const val ARMOR_DR_MAX = 0.25f
        private const val SHIELD_VISUAL_GRACE_SECONDS = 0.18f

        private const val RAMP_START_KEY = "astd_plasma_boost_ramp_start:"

        private val ARC_FRINGE = Color(85, 205, 255, 220)
    }

    override fun apply(stats: MutableShipStatsAPI, id: String, state: ShipSystemStatsScript.State, effectLevel: Float) {
        val level = effectLevel.coerceIn(0f, 1f)
        applyStatModifiers(stats, id, level)

        val engine = Global.getCombatEngine() ?: return
        val ship = stats.entity as? ShipAPI ?: return
        if (ship.isHulk || !ship.isAlive) return

        val shipKey = System.identityHashCode(ship).toString()
        val now = engine.getTotalElapsedTime(false)
        val rampStartKey = "$RAMP_START_KEY$shipKey"
        if (engine.customData[rampStartKey] == null) {
            engine.customData[rampStartKey] = now
        }

        val rampStart = engine.customData[rampStartKey] as? Float ?: now
        val ramp = ((now - rampStart) / RAMP_SECONDS).coerceIn(0f, 1f)
        val rampedLevel = (level * ramp).coerceIn(0f, 1f)

        applyStatModifiers(stats, id, rampedLevel)
        suppressEligibleWeapons(ship)

        ship.setCustomData(ASTDArcProductionShipIds.DATA_PLASMA_SHIELD_BOOST_LEVEL, rampedLevel)
        if (rampedLevel > 0.05f) {
            ASTDArcProductionVfx.setCounter(engine, ASTDArcProductionVfx.TELEMETRY_PLASMA_ARCH_SYSTEM_ACTIVE, 1)
        }
        renderBoostShield(ship, rampedLevel)
    }

    override fun unapply(stats: MutableShipStatsAPI, id: String) {
        stats.shieldDamageTakenMult.unmodify(id)
        stats.armorDamageTakenMult.unmodify(id)
        val ship = stats.entity as? ShipAPI ?: return
        val engine = Global.getCombatEngine() ?: return
        ASTDArcProductionVfx.markPlasmaShieldVisualGrace(engine, ship, SHIELD_VISUAL_GRACE_SECONDS)
        ship.removeCustomData(ASTDArcProductionShipIds.DATA_PLASMA_SHIELD_BOOST_LEVEL)
        val shipKey = System.identityHashCode(ship).toString()
        engine.customData.remove("$RAMP_START_KEY$shipKey")
        ship.setJitterShields(false)
    }

    override fun getStatusData(index: Int, state: ShipSystemStatsScript.State, effectLevel: Float): ShipSystemStatsScript.StatusData? {
        val suffix = when (state) {
            ShipSystemStatsScript.State.IN -> "in"
            ShipSystemStatsScript.State.ACTIVE -> "active"
            ShipSystemStatsScript.State.OUT -> "out"
            else -> return null
        }
        val line = when (index) {
            0 -> "line1"
            1 -> "line2"
            else -> return null
        }
        val level = effectLevel.coerceIn(0f, 1f)
        return ShipSystemStatsScript.StatusData(
            I18n.t(
                I18n.Categories.MOD,
                "system.plasma_armor_shield_boost.status.default.$suffix.$line",
                "shield" to formatPercent(SHIELD_DR_MAX * level),
                "armor" to formatPercent(ARMOR_DR_MAX * level),
            ),
            false,
        )
    }

    private fun formatPercent(value: Float): String =
        "${(value.coerceAtLeast(0f) * 100f).roundToInt()}%"

    private fun applyStatModifiers(stats: MutableShipStatsAPI, id: String, level: Float) {
        val clamped = level.coerceIn(0f, 1f)
        stats.shieldDamageTakenMult.modifyMult(id, 1f - SHIELD_DR_MAX * clamped)
        stats.armorDamageTakenMult.modifyMult(id, 1f - ARMOR_DR_MAX * clamped)
    }

    private fun suppressEligibleWeapons(ship: ShipAPI) {
        for (weapon in try { ship.allWeapons } catch (_: Throwable) { return }) {
            if (!ASTDArcCombatUtil.isNonPdNonMissileWeapon(weapon)) continue
            weapon.setForceNoFireOneFrame(true)
        }
    }

    private fun renderBoostShield(ship: ShipAPI, level: Float) {
        val shield = ship.shield ?: return
        if (!shield.isOn || level <= 0.02f) return

        ship.setJitterShields(true)
        ASTDArcProductionVfx.applyPlasmaShieldVisuals(ship, level)
        ship.setJitter(ASTDArcProductionShipIds.STAT_PLASMA_ARMOR_SHIELD_BOOST, ARC_FRINGE, 0.04f + 0.05f * level, 3, 0f, 5f + 8f * level)
        ship.setJitterUnder(ASTDArcProductionShipIds.STAT_PLASMA_ARMOR_SHIELD_BOOST, Color(60, 160, 255, 125), 0.15f * level, 8, 0f, 12f)
    }
}
