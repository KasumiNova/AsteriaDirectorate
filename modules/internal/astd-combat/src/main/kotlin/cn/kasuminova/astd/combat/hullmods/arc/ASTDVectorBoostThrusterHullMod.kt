package cn.kasuminova.astd.combat.hullmods.arc

import cn.kasuminova.astd.combat.hullmods.HullmodIncompatibility
import cn.kasuminova.astd.combat.hullmods.base.IncompatibleHullmodStripper
import cn.kasuminova.astd.combat.hullmods.base.signedPercentOf
import cn.kasuminova.astd.ui.dsl.HullmodThemes
import cn.kasuminova.astd.ui.dsl.HullmodTone
import cn.kasuminova.astd.ui.dsl.hl
import cn.kasuminova.astd.ui.dsl.hullmodCard
import cn.kasuminova.astd.ui.dsl.v
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.impl.campaign.ids.HullMods
import com.fs.starfarer.api.ui.TooltipMakerAPI
import java.awt.Color

class ASTDVectorBoostThrusterHullMod : BaseHullMod() {

    override fun applyEffectsBeforeShipCreation(hullSize: ShipAPI.HullSize, stats: MutableShipStatsAPI, id: String) {
        IncompatibleHullmodStripper.requestStrip(stats.variant, FORBIDDEN_HULLMOD_IDS, HULLMOD_ID)
    }

    override fun applyEffectsAfterShipCreation(ship: ShipAPI, id: String) {
        IncompatibleHullmodStripper.requestStrip(ship.variant, FORBIDDEN_HULLMOD_IDS, HULLMOD_ID)
        IncompatibleHullmodStripper.drainPending()
    }

    override fun advanceInCombat(ship: ShipAPI, amount: Float) {
        IncompatibleHullmodStripper.drainPending()
        val engine = Global.getCombatEngine() ?: return
        if (engine.isPaused) return

        val detached = ship.isHulk || !ship.isAlive || ship.variant?.hasHullMod(HULLMOD_ID) != true
        if (detached) {
            release(engine, ship)
            return
        }

        val stats = ship.mutableStats
        val ratio = VectorBoostThrusterTuning.speedRatio(
            vectorSpeed(ship.velocity.x, ship.velocity.y),
            ship.maxSpeed,
        )
        val maneuver = VectorBoostThrusterTuning.maneuverPercent(ratio)
        stats.acceleration.modifyPercent(STAT_ID, maneuver)
        stats.deceleration.modifyPercent(STAT_ID, maneuver)
        stats.turnAcceleration.modifyPercent(STAT_ID, maneuver)
        stats.maxTurnRate.modifyPercent(STAT_ID, maneuver)

        stats.zeroFluxSpeedBoost.modifyMult(
            STAT_ID,
            VectorBoostThrusterTuning.zeroFluxMult(ship.hardFluxLevel),
        )
        stats.zeroFluxMinimumFluxLevel.modifyFlat(STAT_ID, ALWAYS_ON_ZERO_FLUX_LEVEL)

        if (VectorBoostThrusterTuning.isMassOwnedBySystem(engine.customData, ship.id)) return
        writeMass(engine, ship, ratio)
    }

    override fun addPostDescriptionSection(
        tooltip: TooltipMakerAPI,
        hullSize: ShipAPI.HullSize,
        ship: ShipAPI?,
        width: Float,
        isForModSpec: Boolean,
    ) {
        tooltip.hullmodCard(width, THEME, spec?.displayName) {
            para("ui.hullmod.vector_boost_thruster.desc")
            heading("ui.hullmod.export.section.effect")
            table {
                row(
                    "ui.hullmod.vector_boost_thruster.table.mass.label",
                    "ui.hullmod.vector_boost_thruster.table.mass.value",
                    HullmodTone.DEFAULT,
                    HullmodTone.HIGHLIGHT,
                    v("pct", signedPercentOf(VectorBoostThrusterTuning.MAX_SPEED_BONUS_FRACTION)),
                )
                row(
                    "ui.hullmod.vector_boost_thruster.table.maneuver.label",
                    "ui.hullmod.vector_boost_thruster.table.maneuver.value",
                    HullmodTone.DEFAULT,
                    HullmodTone.HIGHLIGHT,
                    v("pct", signedPercentOf(VectorBoostThrusterTuning.MAX_SPEED_BONUS_FRACTION)),
                )
                row(
                    "ui.hullmod.vector_boost_thruster.table.zero_flux.label",
                    "ui.hullmod.vector_boost_thruster.table.zero_flux.value",
                    HullmodTone.DEFAULT,
                    HullmodTone.HIGHLIGHT,
                )
            }
            para(
                "ui.hullmod.vector_boost_thruster.line.zero_flux",
                v("full", VectorBoostThrusterTuning.ZERO_FLUX_FULL_FLAT.toInt()),
                v("hard", (VectorBoostThrusterTuning.HARD_FLUX_DECAY_END * 100f).toInt()),
                v("floor", (VectorBoostThrusterTuning.ZERO_FLUX_FLOOR_FRACTION * 100f).toInt()),
            )
            heading("ui.hullmod.vector_boost_thruster.section.limits")
            para("ui.hullmod.vector_boost_thruster.line.limits")
            for (name in incompatibleNames()) {
                para(
                    "ui.hullmod.vector_boost_thruster.line.limit_hullmod",
                    v("name", name),
                    hl("#", HullmodTone.RED),
                    hl(name, HullmodTone.RED),
                )
            }
        }
    }

    override fun showInRefitScreenModPickerFor(ship: ShipAPI): Boolean = false

    override fun getBorderColor(): Color = THEME.borderColor

    override fun getNameColor(): Color = THEME.nameColor

    private fun writeMass(engine: CombatEngineAPI, ship: ShipAPI, ratio: Float) {
        val base = VectorBoostThrusterTuning.resolveBaseMass(engine.customData, ship.id, ship.mass) ?: return
        ship.mass = VectorBoostThrusterTuning.scaledMass(base, ratio)
    }

    private fun release(engine: CombatEngineAPI, ship: ShipAPI) {
        val stats = ship.mutableStats
        stats.acceleration.unmodify(STAT_ID)
        stats.deceleration.unmodify(STAT_ID)
        stats.turnAcceleration.unmodify(STAT_ID)
        stats.maxTurnRate.unmodify(STAT_ID)
        stats.zeroFluxSpeedBoost.unmodifyMult(STAT_ID)
        stats.zeroFluxMinimumFluxLevel.unmodify(STAT_ID)

        val base = VectorBoostThrusterTuning.releaseBaseMass(engine.customData, ship.id) ?: return
        ship.mass = base
    }

    companion object {
        private val THEME = HullmodThemes.ARC

        internal val FORBIDDEN_HULLMOD_IDS: Set<String> =
            HullmodIncompatibility.forbiddenByController(ASTDArcProductionShipIds.HULLMOD_VECTOR_BOOST_THRUSTER)

        private const val HULLMOD_ID = ASTDArcProductionShipIds.HULLMOD_VECTOR_BOOST_THRUSTER
        private const val STAT_ID = ASTDArcProductionShipIds.STAT_VECTOR_BOOST_THRUSTER

        private val INCOMPATIBLE_DISPLAY_IDS: List<String> = listOf(
            HullMods.UNSTABLE_INJECTOR,
            HullMods.AUXILIARY_THRUSTERS,
            HullMods.SAFETYOVERRIDES,
        )

        private fun incompatibleNames(): List<String> =
            INCOMPATIBLE_DISPLAY_IDS.map { Global.getSettings().getHullModSpec(it).displayName }

        /** 原版零辐能加速在总辐能占比不超过该值时启用。抬到 1 后任意辐能水平都保持启用。 */
        private const val ALWAYS_ON_ZERO_FLUX_LEVEL = 1f
    }
}
