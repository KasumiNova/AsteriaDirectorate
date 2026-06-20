package cn.kasuminova.astd.combat.hullmods.lens

import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI

/**
 * 透镜阵列核心双模式切换器：在 refit 中轮换无人/载人（镜像 ARC 切换器）。
 */
class ASTDLensDualModeSwitcherHullMod : BaseHullMod() {

    override fun applyEffectsBeforeShipCreation(hullSize: ShipAPI.HullSize, stats: MutableShipStatsAPI, id: String) {
        val variant = stats.variant ?: return
        if (!variant.isGravitationalLensVariant()) return
        variant.ensureLensArrayModeState(stats)
    }

    override fun isApplicableToShip(ship: ShipAPI): Boolean = ship.isGravitationalLensShip()
    override fun showInRefitScreenModPickerFor(ship: ShipAPI): Boolean = ship.isGravitationalLensShip()
}
