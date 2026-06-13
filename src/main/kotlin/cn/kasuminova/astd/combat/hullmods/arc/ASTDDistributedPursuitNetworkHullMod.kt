package cn.kasuminova.astd.combat.hullmods.arc

import cn.kasuminova.astd.combat.hullmods.base.ASTDHullModTooltipRenderer
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI
import java.awt.Color

class ASTDDistributedPursuitNetworkHullMod : BaseHullMod() {

    companion object {
        private const val MAX_LINKS = 5
        private const val LINK_BONUS = 0.04f
        private const val SAME_NETWORK_MULT = 1.5f
        private const val TARGETS_KEY = "astd_distributed_pursuit_network_targets:"

        private val ELIGIBLE_HULL_SIZES = setOf(ShipAPI.HullSize.FRIGATE, ShipAPI.HullSize.DESTROYER)

        private val THEME = ASTDHullModTooltipRenderer.Theme(
            nameColor = Color(156, 238, 255),
            borderColor = Color(76, 190, 255),
            headerBackground = Color(14, 56, 82, 185),
            sectionBackground = Color(8, 38, 62, 125),
            accentColor = Color(70, 180, 240),
        )
    }

    override fun advanceInCombat(ship: ShipAPI, amount: Float) {
        val engine = Global.getCombatEngine() ?: return
        if (engine.isPaused || amount <= 0f) return
        if (ship.isHulk) {
            clearStaleTargets(engine, ship, emptySet())
            engine.customData.remove(targetsKey(ship))
            return
        }

        val targets = selectNetworkTargets(ship)
        val activeSet = targets.mapTo(LinkedHashSet()) { System.identityHashCode(it) }
        clearStaleTargets(engine, ship, activeSet)
        engine.customData[targetsKey(ship)] = activeSet
        engine.customData[ASTDArcProductionShipIds.DATA_DISTRIBUTED_PURSUIT_TARGETS + ":" + System.identityHashCode(ship)] = targets

        for (target in targets) {
            applyNetworkStats(ship, target, linkStrength(target))
        }
    }

    override fun addPostDescriptionSection(
        tooltip: TooltipMakerAPI,
        hullSize: ShipAPI.HullSize,
        ship: ShipAPI?,
        width: Float,
        isForModSpec: Boolean
    ) {
        ASTDHullModTooltipRenderer.renderBlocks(
            tooltip = tooltip,
            width = width,
            title = spec?.displayName ?: "",
            theme = THEME,
            blocks = ASTDArcProductionTooltipContracts.distributedPursuitNetwork.blocks,
        )
    }

    override fun isApplicableToShip(ship: ShipAPI): Boolean = true

    override fun showInRefitScreenModPickerFor(ship: ShipAPI): Boolean = false

    override fun getBorderColor(): Color = THEME.borderColor

    override fun getNameColor(): Color = THEME.nameColor

    private fun selectNetworkTargets(ship: ShipAPI): List<ShipAPI> {
        val engine = Global.getCombatEngine() ?: return emptyList()
        val candidates = engine.ships
            .asSequence()
            .filter { it !== ship }
            .map { ASTDArcAuraUtil.summaryFor(it) to it }
            .toList()
        val selectedIds = ASTDArcAuraUtil.selectTargets(
            sourceOwner = ship.owner,
            sourceLocation = ship.location,
            maxRange = ASTDArcAuraUtil.RADIATION_BELT_NETWORK_RANGE,
            maxCount = MAX_LINKS,
            eligibleHullSizes = ELIGIBLE_HULL_SIZES,
            candidates = candidates.map { it.first },
        ).mapTo(HashSet()) { it.id }
        return candidates
            .filter { it.first.id in selectedIds }
            .sortedWith(compareBy({ ASTDArcAuraUtil.distance(ship.location, it.second.location) }, { it.first.id }))
            .map { it.second }
    }

    private fun linkStrength(target: ShipAPI): Float =
        if (target.variant?.hasHullMod(ASTDArcProductionShipIds.HULLMOD_DISTRIBUTED_PURSUIT_NETWORK) == true) {
            LINK_BONUS * SAME_NETWORK_MULT
        } else {
            LINK_BONUS
        }

    private fun applyNetworkStats(source: ShipAPI, target: ShipAPI, linkStrength: Float) {
        if (linkStrength <= 0.0001f) return
        val id = targetModId(source, target)
        val stats = target.mutableStats
        val mult = 1f + linkStrength
        stats.maxSpeed.modifyMult(id, mult)
        stats.acceleration.modifyMult(id, mult)
        stats.deceleration.modifyMult(id, mult)
        stats.maxTurnRate.modifyMult(id, mult)
        stats.turnAcceleration.modifyMult(id, mult)
        stats.ballisticWeaponRangeBonus.modifyPercent(id, linkStrength * 100f)
        stats.energyWeaponRangeBonus.modifyPercent(id, linkStrength * 100f)
        stats.beamWeaponRangeBonus.modifyPercent(id, linkStrength * 100f)
        stats.peakCRDuration.modifyMult(id, 1f / (1f - linkStrength).coerceAtLeast(0.1f))
    }

    private fun clearStaleTargets(engine: CombatEngineAPI, source: ShipAPI, activeSet: Set<Int>) {
        val previous = engine.customData[targetsKey(source)] as? Set<*> ?: emptySet<Any>()
        val ships = try { engine.ships } catch (_: Throwable) { null } ?: return
        for (entry in previous) {
            val identity = entry as? Int ?: continue
            if (identity in activeSet) continue
            val target = ships.firstOrNull { System.identityHashCode(it) == identity } ?: continue
            clearNetworkStats(source, target)
        }
    }

    private fun clearNetworkStats(source: ShipAPI, target: ShipAPI) {
        val id = targetModId(source, target)
        val stats = target.mutableStats
        stats.maxSpeed.unmodify(id)
        stats.acceleration.unmodify(id)
        stats.deceleration.unmodify(id)
        stats.maxTurnRate.unmodify(id)
        stats.turnAcceleration.unmodify(id)
        stats.ballisticWeaponRangeBonus.unmodify(id)
        stats.energyWeaponRangeBonus.unmodify(id)
        stats.beamWeaponRangeBonus.unmodify(id)
        stats.peakCRDuration.unmodify(id)
    }

    private fun targetsKey(source: ShipAPI): String = "$TARGETS_KEY${System.identityHashCode(source)}"

    private fun targetModId(source: ShipAPI, target: ShipAPI): String =
        "${ASTDArcProductionShipIds.STAT_DISTRIBUTED_PURSUIT_NETWORK}:${System.identityHashCode(source)}:${System.identityHashCode(target)}"

}
