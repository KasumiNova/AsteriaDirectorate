package cn.kasuminova.astd.combat.hullmods.arc

import cn.kasuminova.astd.combat.hullmods.base.ASTDHullModTooltipRenderer
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI
import java.awt.Color

class ASTDDistributedPursuitNetworkHullMod : BaseHullMod() {

    companion object {
        private const val MAX_LINKS = 5
        private const val LINK_BONUS = 0.04f
        private const val SAME_NETWORK_MULT = 1.5f
        private const val LINK_PULSE_INTERVAL = 0.85f

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
        if (engine.isPaused || amount <= 0f || ship.isHulk) return

        val targets = selectNetworkTargets(ship)
        engine.customData[ASTDArcProductionShipIds.DATA_DISTRIBUTED_PURSUIT_TARGETS + ":" + System.identityHashCode(ship)] = targets

        val linkStrength = targets.sumOf { target ->
            (if (target.variant?.hasHullMod(ASTDArcProductionShipIds.HULLMOD_DISTRIBUTED_PURSUIT_NETWORK) == true) {
                LINK_BONUS * SAME_NETWORK_MULT
            } else {
                LINK_BONUS
            }).toDouble()
        }.toFloat()

        applyNetworkStats(ship, linkStrength)
        renderNetworkPulse(ship, targets, amount)
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

    private fun applyNetworkStats(ship: ShipAPI, linkStrength: Float) {
        val id = ASTDArcProductionShipIds.STAT_DISTRIBUTED_PURSUIT_NETWORK
        val stats = ship.mutableStats
        if (linkStrength <= 0.0001f) {
            stats.maxSpeed.unmodify(id)
            stats.acceleration.unmodify(id)
            stats.deceleration.unmodify(id)
            stats.maxTurnRate.unmodify(id)
            stats.turnAcceleration.unmodify(id)
            stats.ballisticWeaponRangeBonus.unmodify(id)
            stats.energyWeaponRangeBonus.unmodify(id)
            stats.beamWeaponRangeBonus.unmodify(id)
            stats.peakCRDuration.unmodify(id)
            return
        }

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

    private fun renderNetworkPulse(ship: ShipAPI, targets: List<ShipAPI>, amount: Float) {
        val engine = Global.getCombatEngine() ?: return
        val key = "${ASTDArcProductionShipIds.STAT_DISTRIBUTED_PURSUIT_NETWORK}:pulse:${System.identityHashCode(ship)}"
        val elapsed = ((engine.customData[key] as? Float) ?: 0f) + amount
        if (elapsed < LINK_PULSE_INTERVAL) {
            engine.customData[key] = elapsed
            return
        }
        engine.customData[key] = elapsed - LINK_PULSE_INTERVAL

        for (target in targets) {
            val sameNetwork = target.variant?.hasHullMod(ASTDArcProductionShipIds.HULLMOD_DISTRIBUTED_PURSUIT_NETWORK) == true
            ASTDArcProductionVfx.emitRadiationPursuitPing(engine, ship, target, sameNetwork)
        }
    }
}
