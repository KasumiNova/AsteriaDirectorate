package cn.kasuminova.astd.combat.hullmods.arc

import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.combat.hullmods.HullmodIncompatibility
import cn.kasuminova.astd.combat.hullmods.base.IncompatibleHullmodStripper
import cn.kasuminova.astd.combat.hullmods.base.signedPercentOf
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import cn.kasuminova.astd.internal.i18n.I18n
import cn.kasuminova.astd.ui.dsl.HullmodThemes
import cn.kasuminova.astd.ui.dsl.HullmodTone
import cn.kasuminova.astd.ui.dsl.v
import cn.kasuminova.astd.ui.dsl.hl
import cn.kasuminova.astd.ui.dsl.hullmodCard
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipVariantAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI
import java.awt.Color

class ASTDAdvancedEnergyIntegrationHullMod : BaseHullMod() {

    companion object {
        const val MOD_ID = "astd_arc_advanced_energy_integration"

        private val FORBIDDEN_HULLMOD_IDS: Set<String> =
            HullmodIncompatibility.forbiddenByController(MOD_ID)

        val WEAPON_FLUX = ScalingEntry(0.90f, 0.85f, 0.70f)

        val PROJ_SPEED = ScalingEntry(1.15f, 1.20f, 1.35f)

        val TURN_RATE = ScalingEntry(1.25f, 1.50f, 2.25f)

        private const val SMALL_OP_DISCOUNT = -2f
        private const val MEDIUM_OP_DISCOUNT = -4f
        private const val LARGE_OP_DISCOUNT = -8f

        private val THEME = HullmodThemes.ARC
    }

    internal data class Values(
        val weaponFluxMult: Float,
        val projSpeedMult: Float,
        val turnRateMult: Float,
    ) {
        companion object {
            fun resolve(isPlayer: Boolean): Values = Values(
                weaponFluxMult = DifficultyTuningImpl.valueFor(WEAPON_FLUX, isPlayer),
                projSpeedMult = DifficultyTuningImpl.valueFor(PROJ_SPEED, isPlayer),
                turnRateMult = DifficultyTuningImpl.valueFor(TURN_RATE, isPlayer),
            )
        }
    }

    override fun applyEffectsBeforeShipCreation(hullSize: ShipAPI.HullSize, stats: MutableShipStatsAPI, id: String) {
        val variant = stats.variant ?: return
        if (!variant.isASTDXc001Variant()) return

        // 只检测入队：本回调可能在原版 OP 结算的 hullMods 实时迭代内触发，结构性移除会抛 CME
        IncompatibleHullmodStripper.requestStrip(variant, FORBIDDEN_HULLMOD_IDS, MOD_ID)

        stats.dynamic.getMod("small_ballistic_mod").modifyFlat(id, SMALL_OP_DISCOUNT)
        stats.dynamic.getMod("small_energy_mod").modifyFlat(id, SMALL_OP_DISCOUNT)
        stats.dynamic.getMod("medium_ballistic_mod").modifyFlat(id, MEDIUM_OP_DISCOUNT)
        stats.dynamic.getMod("medium_energy_mod").modifyFlat(id, MEDIUM_OP_DISCOUNT)
        stats.dynamic.getMod("large_ballistic_mod").modifyFlat(id, LARGE_OP_DISCOUNT)
        stats.dynamic.getMod("large_energy_mod").modifyFlat(id, LARGE_OP_DISCOUNT)
    }

    override fun applyEffectsAfterShipCreation(ship: ShipAPI, id: String) {
        // ShipFactory 迭代 getAllMods() 快照，此处入队并立即清理是安全的
        IncompatibleHullmodStripper.requestStrip(ship.variant, FORBIDDEN_HULLMOD_IDS, MOD_ID)
        IncompatibleHullmodStripper.drainPending()
    }

    override fun advanceInCombat(ship: ShipAPI, amount: Float) {
        IncompatibleHullmodStripper.drainPending()
        if (!ship.isASTDXc001Ship() || ship.isHulk) return

        val values = Values.resolve(ship.owner == 0)
        val stats = ship.mutableStats
        stats.ballisticWeaponFluxCostMod.modifyMult(MOD_ID, values.weaponFluxMult)
        stats.energyWeaponFluxCostMod.modifyMult(MOD_ID, values.weaponFluxMult)
        stats.beamWeaponFluxCostMult.modifyMult(MOD_ID, values.weaponFluxMult)
        stats.missileWeaponFluxCostMod.modifyMult(MOD_ID, values.weaponFluxMult)
        stats.ballisticProjectileSpeedMult.modifyMult(MOD_ID, values.projSpeedMult)
        stats.energyProjectileSpeedMult.modifyMult(MOD_ID, values.projSpeedMult)
        stats.weaponTurnRateBonus.modifyMult(MOD_ID, values.turnRateMult)
    }

    override fun addPostDescriptionSection(
        tooltip: TooltipMakerAPI,
        hullSize: ShipAPI.HullSize,
        ship: ShipAPI?,
        width: Float,
        isForModSpec: Boolean
    ) {
        val values = Values.resolve(ship == null || ship.owner == 0)
        tooltip.hullmodCard(width, THEME, spec?.displayName) {
            para("ui.hullmod.aei.summary")
            heading("ui.hullmod.export.section.effect")
            table {
                row("ui.hullmod.aei.attr.op", "ui.hullmod.aei.value.op", HullmodTone.DEFAULT, HullmodTone.HIGHLIGHT)
                row(
                    "ui.hullmod.aei.attr.flux", "ui.hullmod.aei.value.flux",
                    HullmodTone.DEFAULT, HullmodTone.HIGHLIGHT,
                    v("pct", signedPercentOf(values.weaponFluxMult - 1f)),
                )
                row(
                    "ui.hullmod.aei.attr.projectile_speed", "ui.hullmod.aei.value.projectile_speed",
                    HullmodTone.DEFAULT, HullmodTone.HIGHLIGHT,
                    v("pct", signedPercentOf(values.projSpeedMult - 1f)),
                )
                row(
                    "ui.hullmod.aei.attr.turn_rate", "ui.hullmod.aei.value.turn_rate",
                    HullmodTone.DEFAULT, HullmodTone.HIGHLIGHT,
                    v("pct", signedPercentOf(values.turnRateMult - 1f)),
                )
            }
            heading("ui.hullmod.aei.section.limits")
            para("ui.hullmod.aei.line.limits")
            para("ui.hullmod.aei.line.limit_armoredweapons", hl("#", HullmodTone.RED), hl("炮塔装甲", HullmodTone.RED))
            para("ui.hullmod.aei.line.limit_turretgyros", hl("#", HullmodTone.RED), hl("高级炮塔陀螺仪", HullmodTone.RED))
            spacer(6f)
            para("ui.hullmod.export.difficulty_note", hl("难度系数", HullmodTone.DEFAULT))
        }
    }

    override fun showInRefitScreenModPickerFor(ship: ShipAPI): Boolean = false

    override fun affectsOPCosts(): Boolean = true

    override fun isApplicableToShip(ship: ShipAPI): Boolean {
        if (ship.variant?.let(::hasForbiddenHullMod) == true) return false
        return ship.isASTDXc001Ship()
    }

    override fun getUnapplicableReason(ship: ShipAPI): String? {
        if (ship.variant?.let(::hasForbiddenHullMod) == true) {
            return I18n[I18n.Categories.MOD, "ui.hullmod.aei.unapplicable.turret_mods"]
        }
        return null
    }

    override fun getBorderColor(): Color = THEME.borderColor

    override fun getNameColor(): Color = THEME.nameColor

    private fun hasForbiddenHullMod(variant: ShipVariantAPI): Boolean =
        FORBIDDEN_HULLMOD_IDS.any { forbiddenId ->
            variant.hasHullMod(forbiddenId) ||
                    variant.permaMods.contains(forbiddenId) ||
                    variant.sMods.contains(forbiddenId) ||
                    variant.sModdedBuiltIns.contains(forbiddenId) ||
                    variant.suppressedMods.contains(forbiddenId)
        }
}
