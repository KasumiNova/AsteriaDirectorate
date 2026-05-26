package cn.kasuminova.astd.combat.hullmods.arc

import cn.kasuminova.astd.combat.hullmods.base.ASTDHullModTooltipRenderer
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI
import java.awt.Color

class ASTDArcSharedTacticalNetworkHullMod : BaseHullMod() {

    companion object {
        private const val SELF_WEAPON_RANGE_PERCENT = -20f
        private const val SELF_ECM_RATING = 0.04f
        private const val MAX_TARGETS = 12
        private const val CONNECT_PULSE_MIN_INTERVAL = 1.25f
        private const val TARGETS_KEY = "astd_arc_shared_tactical_network_targets:"
        private const val CONNECT_PULSE_KEY = "astd_arc_shared_tactical_network_connect:"

        private val ELIGIBLE_HULL_SIZES = setOf(
            ShipAPI.HullSize.FRIGATE,
            ShipAPI.HullSize.DESTROYER,
            ShipAPI.HullSize.CRUISER,
        )

        private val THEME = ASTDHullModTooltipRenderer.Theme(
            nameColor = Color(150, 232, 255),
            borderColor = Color(90, 180, 255),
            headerBackground = Color(20, 52, 82, 180),
            sectionBackground = Color(14, 36, 58, 120),
            accentColor = Color(60, 140, 220),
        )
    }

    override fun applyEffectsBeforeShipCreation(hullSize: ShipAPI.HullSize, stats: MutableShipStatsAPI, id: String) {
        val modId = ASTDArcProductionShipIds.STAT_ARC_SHARED_TACTICAL_NETWORK_SELF
        stats.ballisticWeaponRangeBonus.modifyPercent(modId, SELF_WEAPON_RANGE_PERCENT)
        stats.energyWeaponRangeBonus.modifyPercent(modId, SELF_WEAPON_RANGE_PERCENT)
        stats.dynamic.getMod("opad_ecm_rating").modifyFlat(modId, SELF_ECM_RATING)
    }

    override fun advanceInCombat(ship: ShipAPI, amount: Float) {
        val engine = Global.getCombatEngine() ?: return
        if (engine.isPaused || amount <= 0f) return
        if (ship.isHulk || ship.hitpoints <= 0f) {
            clearStaleTargets(engine, ship, emptySet())
            engine.customData.remove("$TARGETS_KEY${System.identityHashCode(ship)}")
            return
        }

        val targets = selectTargets(engine, ship)
        val activeSet = targets.mapTo(LinkedHashSet()) { System.identityHashCode(it) }
        clearStaleTargets(engine, ship, activeSet)

        for (target in targets) {
            val distance = ASTDArcAuraUtil.distance(ship.location, target.location)
            val falloff = ASTDArcAuraUtil.arcJetPassiveFalloff(distance)
            if (falloff <= 0f) continue
            applyAuraToTarget(ship, target, falloff)
            emitPassiveConnectionVfx(engine, ship, target, falloff, amount)
        }

        engine.customData["$TARGETS_KEY${System.identityHashCode(ship)}"] = activeSet
    }

    private fun selectTargets(engine: CombatEngineAPI, source: ShipAPI): List<ShipAPI> {
        val candidates = try { engine.ships } catch (_: Throwable) { null } ?: return emptyList()
        val byId = LinkedHashMap<Int, ShipAPI>()
        for (candidate in candidates) {
            if (candidate === source) continue
            byId[System.identityHashCode(candidate)] = candidate
        }
        val selected = ASTDArcAuraUtil.selectTargets(
            sourceOwner = source.owner,
            sourceLocation = source.location,
            maxRange = ASTDArcAuraUtil.ARC_JET_PASSIVE_MAX_RANGE,
            maxCount = MAX_TARGETS,
            eligibleHullSizes = ELIGIBLE_HULL_SIZES,
            candidates = byId.values.map { ASTDArcAuraUtil.summaryFor(it) },
        )
        return selected.mapNotNull { summary ->
            byId.values.firstOrNull { ASTDArcAuraUtil.summaryFor(it).id == summary.id }
        }
    }

    private fun applyAuraToTarget(source: ShipAPI, target: ShipAPI, falloff: Float) {
        val base = when (target.hullSize) {
            ShipAPI.HullSize.FRIGATE -> AuraBase(rangeBonus = 0.40f, maneuverBonus = 0.20f, shieldDamageReduction = 0.20f)
            ShipAPI.HullSize.DESTROYER -> AuraBase(rangeBonus = 0.30f, maneuverBonus = 0.10f, shieldDamageReduction = 0.15f)
            ShipAPI.HullSize.CRUISER -> AuraBase(rangeBonus = 0.20f, maneuverBonus = 0f, shieldDamageReduction = 0.10f)
            else -> return
        }
        val affinity = affinityScale(target)
        val level = (falloff * affinity).coerceAtLeast(0f)
        val modId = auraModId(source, target)
        val stats = target.mutableStats
        stats.ballisticWeaponRangeBonus.modifyMult(modId, 1f + base.rangeBonus * level)
        stats.energyWeaponRangeBonus.modifyMult(modId, 1f + base.rangeBonus * level)
        stats.beamWeaponRangeBonus.modifyMult(modId, 1f + base.rangeBonus * level)
        if (base.maneuverBonus > 0f) {
            val maneuverMult = 1f + base.maneuverBonus * level
            stats.maxSpeed.modifyMult(modId, maneuverMult)
            stats.acceleration.modifyMult(modId, maneuverMult)
            stats.deceleration.modifyMult(modId, maneuverMult)
            stats.maxTurnRate.modifyMult(modId, maneuverMult)
            stats.turnAcceleration.modifyMult(modId, maneuverMult)
        } else {
            stats.maxSpeed.unmodify(modId)
            stats.acceleration.unmodify(modId)
            stats.deceleration.unmodify(modId)
            stats.maxTurnRate.unmodify(modId)
            stats.turnAcceleration.unmodify(modId)
        }
        stats.shieldDamageTakenMult.modifyMult(modId, 1f - base.shieldDamageReduction * level)
    }

    private fun clearStaleTargets(engine: CombatEngineAPI, source: ShipAPI, activeSet: Set<Int>) {
        val key = "$TARGETS_KEY${System.identityHashCode(source)}"
        val previous = engine.customData[key] as? Set<*> ?: emptySet<Any>()
        val ships = try { engine.ships } catch (_: Throwable) { null } ?: return
        for (entry in previous) {
            val identity = entry as? Int ?: continue
            if (identity in activeSet) continue
            val target = ships.firstOrNull { System.identityHashCode(it) == identity } ?: continue
            clearAuraFromTarget(source, target)
        }
    }

    private fun clearAuraFromTarget(source: ShipAPI, target: ShipAPI) {
        val modId = auraModId(source, target)
        val stats = target.mutableStats
        stats.ballisticWeaponRangeBonus.unmodify(modId)
        stats.energyWeaponRangeBonus.unmodify(modId)
        stats.beamWeaponRangeBonus.unmodify(modId)
        stats.maxSpeed.unmodify(modId)
        stats.acceleration.unmodify(modId)
        stats.deceleration.unmodify(modId)
        stats.maxTurnRate.unmodify(modId)
        stats.turnAcceleration.unmodify(modId)
        stats.shieldDamageTakenMult.unmodify(modId)
    }

    private fun emitPassiveConnectionVfx(engine: CombatEngineAPI, source: ShipAPI, target: ShipAPI, falloff: Float, amount: Float) {
        val key = "$CONNECT_PULSE_KEY${System.identityHashCode(source)}:${System.identityHashCode(target)}"
        val timer = ((engine.customData[key] as? Float ?: CONNECT_PULSE_MIN_INTERVAL) + amount)
        if (timer < CONNECT_PULSE_MIN_INTERVAL) {
            engine.customData[key] = timer
            return
        }
        engine.customData[key] = 0f
        ASTDArcProductionVfx.emitArcJetPassiveLink(engine, source, target, falloff)
    }

    override fun addPostDescriptionSection(tooltip: TooltipMakerAPI, hullSize: ShipAPI.HullSize, ship: ShipAPI?, width: Float, isForModSpec: Boolean) {
        ASTDHullModTooltipRenderer.renderBlocks(
            tooltip = tooltip,
            width = width,
            title = spec?.displayName ?: "",
            theme = THEME,
            blocks = ASTDArcProductionTooltipContracts.arcSharedTacticalNetwork.blocks,
        )
    }

    override fun showInRefitScreenModPickerFor(ship: ShipAPI): Boolean = false

    override fun getBorderColor(): Color = THEME.borderColor

    override fun getNameColor(): Color = THEME.nameColor

    private data class AuraBase(
        val rangeBonus: Float,
        val maneuverBonus: Float,
        val shieldDamageReduction: Float,
    )

    private fun affinityScale(ship: ShipAPI): Float {
        var scale = 1f
        if (ASTDArcAuraUtil.isASTDHull(ship)) scale += 0.25f
        if (ASTDArcAuraUtil.isArcProductionHull(ship)) scale += 0.25f
        return scale
    }

    private fun auraModId(source: ShipAPI, target: ShipAPI): String =
        "${ASTDArcProductionShipIds.STAT_ARC_SHARED_TACTICAL_NETWORK_AURA}:${System.identityHashCode(source)}:${System.identityHashCode(target)}"
}
