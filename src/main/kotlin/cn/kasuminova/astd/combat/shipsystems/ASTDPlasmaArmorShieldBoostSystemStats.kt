package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.combat.hullmods.arc.ASTDArcProductionShipIds
import cn.kasuminova.astd.combat.hullmods.arc.ASTDArcProductionVfx
import cn.kasuminova.astd.combat.hullmods.arc.ASTDArcCombatUtil
import cn.kasuminova.astd.internal.i18n.I18n
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript
import com.fs.starfarer.api.plugins.ShipSystemStatsScript
import java.awt.Color

class ASTDPlasmaArmorShieldBoostSystemStats : BaseShipSystemScript() {

    companion object {
        private const val RAMP_SECONDS = 2f
        private const val SHIELD_DR_MAX = 0.50f
        private const val ARMOR_DR_MAX = 0.25f
        private const val WEAPON_ROF_MULT = 0.25f
        private const val HARD_FLUX_PER_SECOND = 0.02f

        private const val RAMP_START_KEY = "astd_plasma_boost_ramp_start:"
        private const val FLUX_TIME_KEY = "astd_plasma_boost_flux_time:"

        private val ARC_FRINGE = Color(85, 205, 255, 220)
    }

    override fun apply(stats: MutableShipStatsAPI, id: String, state: ShipSystemStatsScript.State, effectLevel: Float) {
        val engine = Global.getCombatEngine() ?: return
        val ship = stats.entity as? ShipAPI ?: return
        if (ship.isHulk || !ship.isAlive) return

        val shipKey = System.identityHashCode(ship).toString()
        val now = engine.getTotalElapsedTime(false)
        val rampStartKey = "$RAMP_START_KEY$shipKey"
        if (engine.customData[rampStartKey] == null) {
            engine.customData[rampStartKey] = now
            engine.customData["$FLUX_TIME_KEY$shipKey"] = now
        }

        val rampStart = engine.customData[rampStartKey] as? Float ?: now
        val ramp = ((now - rampStart) / RAMP_SECONDS).coerceIn(0f, 1f)
        val level = (effectLevel * ramp).coerceIn(0f, 1f)

        stats.shieldDamageTakenMult.modifyMult(id, 1f - SHIELD_DR_MAX * level)
        stats.armorDamageTakenMult.modifyMult(id, 1f - ARMOR_DR_MAX * level)
        suppressEligibleWeaponRefire(ship, level)

        ship.setCustomData(ASTDArcProductionShipIds.DATA_PLASMA_SHIELD_BOOST_LEVEL, level)
        if (level > 0.05f) {
            ASTDArcProductionVfx.setCounter(engine, ASTDArcProductionVfx.TELEMETRY_PLASMA_ARCH_SYSTEM_ACTIVE, 1)
        }
        generateHardFlux(engine, ship, shipKey)
        renderBoostShield(ship, level)
    }

    override fun unapply(stats: MutableShipStatsAPI, id: String) {
        stats.shieldDamageTakenMult.unmodify(id)
        stats.armorDamageTakenMult.unmodify(id)
        val ship = stats.entity as? ShipAPI ?: return
        ASTDArcProductionVfx.applyPlasmaShieldVisuals(ship, 0f)
        ship.removeCustomData(ASTDArcProductionShipIds.DATA_PLASMA_SHIELD_BOOST_LEVEL)
        ASTDArcCombatUtil.restoreRefireDelays(ship)
        val engine = Global.getCombatEngine() ?: return
        val shipKey = System.identityHashCode(ship).toString()
        engine.customData.remove("$RAMP_START_KEY$shipKey")
        engine.customData.remove("$FLUX_TIME_KEY$shipKey")
        ship.setJitterShields(false)
    }

    override fun getStatusData(index: Int, state: ShipSystemStatsScript.State, effectLevel: Float): ShipSystemStatsScript.StatusData? {
        if (index != 0) return null
        val suffix = when (state) {
            ShipSystemStatsScript.State.IN -> "in"
            ShipSystemStatsScript.State.ACTIVE -> "active"
            ShipSystemStatsScript.State.OUT -> "out"
            else -> return null
        }
        return ShipSystemStatsScript.StatusData(
            I18n[I18n.Categories.MOD, "system.plasma_armor_shield_boost.status.default.$suffix"],
            false,
        )
    }

    private fun suppressEligibleWeaponRefire(ship: ShipAPI, level: Float) {
        val mult = 1f + (WEAPON_ROF_MULT - 1f) * level.coerceIn(0f, 1f)
        if (mult >= 0.999f) {
            ASTDArcCombatUtil.restoreRefireDelays(ship)
            return
        }
        for (weapon in try { ship.allWeapons } catch (_: Throwable) { return }) {
            if (!ASTDArcCombatUtil.isNonPdNonMissileWeapon(weapon)) continue
            ASTDArcCombatUtil.applyRefireDelayMult(weapon, mult)
        }
    }

    private fun generateHardFlux(engine: CombatEngineAPI, ship: ShipAPI, shipKey: String) {
        val key = "$FLUX_TIME_KEY$shipKey"
        val now = engine.getTotalElapsedTime(false)
        val previous = engine.customData[key] as? Float ?: now
        val amount = (now - previous).coerceIn(0f, 0.25f)
        engine.customData[key] = now
        if (amount <= 0f) return
        ship.fluxTracker.increaseFlux(ship.fluxTracker.maxFlux * HARD_FLUX_PER_SECOND * amount, true)
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
