package cn.kasuminova.astd.combat.hullmods.lens

import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI

/**
 * 透镜阵列核心·载人模式（宏观锚定态，spec §3.1）。
 * - 设置载人版「回声定影」系统 ID。
 * - 情报中枢（全队 ECM 按友军数量/等级）与战术链路（对带误差标记目标增伤）
 *   在 ASTDLensArrayCoreHullMod.advanceInCombat 处理（需遍历友军/目标标记）。
 */
class ASTDLensCrewedModeHullMod : BaseHullMod() {

    override fun applyEffectsBeforeShipCreation(hullSize: ShipAPI.HullSize, stats: MutableShipStatsAPI, id: String) {
        val variant = stats.variant ?: return
        if (!variant.isGravitationalLensVariant()) return
        variant.hullSpec?.setShipSystemId(LensArrayCoreHullModIds.SYSTEM_CREWED)
    }

    override fun isApplicableToShip(ship: ShipAPI): Boolean = ship.isGravitationalLensShip()
    override fun showInRefitScreenModPickerFor(ship: ShipAPI): Boolean = false
}
