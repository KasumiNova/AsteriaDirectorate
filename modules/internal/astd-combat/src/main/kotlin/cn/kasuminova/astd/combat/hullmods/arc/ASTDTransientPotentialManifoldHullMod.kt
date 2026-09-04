package cn.kasuminova.astd.combat.hullmods.arc

import cn.kasuminova.astd.combat.hullmods.base.ASTDHullModTooltipRenderer
import cn.kasuminova.astd.renderer.effect.hullmods.ASTDNegentropyChargeBarRenderer
import cn.kasuminova.astd.combat.shipsystems.ASTDNegentropyEdgeState
import cn.kasuminova.astd.combat.shipsystems.ASTDNegentropyEdgeDroneSubsystem
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI
import org.magiclib.subsystems.MagicSubsystemsManager
import java.awt.Color

class ASTDTransientPotentialManifoldHullMod : BaseHullMod() {

    companion object {
        const val CHARGE_DECAY_PER_SEC = 0.03f
        const val RELOAD_HEAT_DISSIPATION_BONUS = 150f
        private const val FLUX_DISSIPATION_BONUS = 50f
        private const val WEAPON_TURN_MULT = 1.20f
        private const val MEDIUM_HYBRID_OP_DISCOUNT = -3f
        private const val MOD_ID = "astd_transient_potential_manifold"
        private const val RELOAD_MOD_ID = "astd_transient_potential_reload_heat"

        private val THEME = ASTDHullModTooltipRenderer.Theme(
            nameColor = Color(150, 225, 255),
            borderColor = Color(86, 180, 255),
            headerBackground = Color(14, 46, 78, 190),
            sectionBackground = Color(10, 34, 56, 130),
            accentColor = Color(80, 170, 240),
        )
    }

    override fun applyEffectsBeforeShipCreation(hullSize: ShipAPI.HullSize, stats: MutableShipStatsAPI, id: String) {
        val variant = stats.variant ?: return
        if (variant.hullSpec?.hullId != ASTDNegentropyEdgeState.HULL_ID) return
        stats.dynamic.getMod("medium_ballistic_mod").modifyFlat(id, MEDIUM_HYBRID_OP_DISCOUNT)
        stats.dynamic.getMod("medium_energy_mod").modifyFlat(id, MEDIUM_HYBRID_OP_DISCOUNT)
        stats.dynamic.getMod("medium_missile_mod").modifyFlat(id, MEDIUM_HYBRID_OP_DISCOUNT)
        stats.weaponTurnRateBonus.modifyMult(id, WEAPON_TURN_MULT)
        stats.fluxDissipation.modifyFlat(id, FLUX_DISSIPATION_BONUS)
    }

    override fun applyEffectsAfterShipCreation(ship: ShipAPI, id: String) {
        if (!ASTDNegentropyEdgeState.isNegentropyEdge(ship)) return
        try {
            MagicSubsystemsManager.addSubsystemToShip(ship, ASTDNegentropyEdgeDroneSubsystem(ship))
        } catch (_: Throwable) {
        }
    }

    override fun advanceInCombat(ship: ShipAPI, amount: Float) {
        val engine = Global.getCombatEngine() ?: return
        if (engine.isPaused || amount <= 0f || ship.isHulk || !ASTDNegentropyEdgeState.isNegentropyEdge(ship)) return
        ASTDNegentropyChargeBarRenderer.ensure(engine)
        ASTDNegentropyEdgeState.decayCharge(ship, CHARGE_DECAY_PER_SEC * amount)
        ASTDNegentropyEdgeState.advanceWindows(ship, amount)

        val reloading = ship.allWeapons.any { w ->
            try { w.spec?.weaponId == ASTDNegentropyEdgeState.SPC3_WEAPON_ID && w.ammo <= 0 && w.maxAmmo > 0 } catch (_: Throwable) { false }
        }
        if (reloading) {
            ship.mutableStats.fluxDissipation.modifyFlat(RELOAD_MOD_ID, RELOAD_HEAT_DISSIPATION_BONUS)
        } else {
            ship.mutableStats.fluxDissipation.unmodify(RELOAD_MOD_ID)
        }
    }

    override fun addPostDescriptionSection(tooltip: TooltipMakerAPI, hullSize: ShipAPI.HullSize, ship: ShipAPI?, width: Float, isForModSpec: Boolean) {
        ASTDHullModTooltipRenderer.renderBlocks(
            tooltip = tooltip,
            width = width,
            title = spec?.displayName ?: "",
            theme = THEME,
            blocks = listOf(
                ASTDHullModTooltipRenderer.paragraph("ui.hullmod.negentropy.manifold.summary"),
                ASTDHullModTooltipRenderer.heading("ui.hullmod.export.section.impact"),
                ASTDHullModTooltipRenderer.table(
                    rows = arrayOf(
                        ASTDHullModTooltipRenderer.row("ui.hullmod.negentropy.manifold.attr.op", "ui.hullmod.negentropy.manifold.value.op"),
                        ASTDHullModTooltipRenderer.row("ui.hullmod.negentropy.manifold.attr.range", "ui.hullmod.negentropy.manifold.value.range"),
                        ASTDHullModTooltipRenderer.row("ui.hullmod.negentropy.manifold.attr.projectile_speed", "ui.hullmod.negentropy.manifold.value.projectile_speed"),
                    ),
                ),
                ASTDHullModTooltipRenderer.heading("ui.hullmod.export.section.pulse"),
                ASTDHullModTooltipRenderer.paragraph("ui.hullmod.negentropy.manifold.pulse.1"),
                ASTDHullModTooltipRenderer.paragraph("ui.hullmod.negentropy.manifold.pulse.2"),
                ASTDHullModTooltipRenderer.paragraph("ui.hullmod.negentropy.manifold.pulse.3"),
                ASTDHullModTooltipRenderer.heading("ui.hullmod.export.section.pulse_active"),
                ASTDHullModTooltipRenderer.table(
                    rows = arrayOf(
                        ASTDHullModTooltipRenderer.row("ui.hullmod.negentropy.manifold.attr.rof", "ui.hullmod.negentropy.manifold.value.rof"),
                        ASTDHullModTooltipRenderer.row("ui.hullmod.negentropy.manifold.attr.damage", "ui.hullmod.negentropy.manifold.value.damage"),
                        ASTDHullModTooltipRenderer.row("ui.hullmod.negentropy.manifold.attr.flux", "ui.hullmod.negentropy.manifold.value.flux"),
                        ASTDHullModTooltipRenderer.row("ui.hullmod.negentropy.manifold.attr.ammo_regen", "ui.hullmod.negentropy.manifold.value.ammo_regen"),
                    ),
                ),
            ),
        )
    }

    override fun affectsOPCosts(): Boolean = true

    override fun showInRefitScreenModPickerFor(ship: ShipAPI): Boolean = false

    override fun isApplicableToShip(ship: ShipAPI): Boolean = ASTDNegentropyEdgeState.isNegentropyEdge(ship)

    override fun getBorderColor(): Color = THEME.borderColor

    override fun getNameColor(): Color = THEME.nameColor
}
