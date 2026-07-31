package cn.kasuminova.astd.combat.hullmods.lens

import cn.kasuminova.astd.combat.hullmods.base.activateDualMode
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI

/**
 * 透镜阵列核心·无人模式（全域拓扑态，spec §3.1）。
 * - 蜂群思维：舰载机非导弹武器射程 +20%，导弹飞行速度/机动 +20%，最大航速 +20%。
 * - 设置无人版「回声定影」系统 ID。
 * 「幽灵信号」范围导弹失制导在核心 hullmod 的 advanceInCombat 处理（需遍历范围）。
 */
class ASTDLensAutomatedModeHullMod : BaseHullMod() {

    companion object {
        private const val FIGHTER_WING_RANGE_MULT = 1.20f
        private const val MAX_SPEED_MULT = 1.20f
        private const val MISSILE_SPEED_MULT = 1.20f
        private const val MISSILE_ACCEL_MULT = 1.20f
        private const val MISSILE_TURN_RATE_MULT = 1.20f
        private const val MISSILE_TURN_ACCEL_MULT = 1.20f
    }

    override fun applyEffectsBeforeShipCreation(hullSize: ShipAPI.HullSize, stats: MutableShipStatsAPI, id: String) {
        val variant = stats.variant ?: return
        if (!variant.isGravitationalLensVariant()) return

        // 拆即切（核心修复，镜像 arc）：通用切换器被玩家在 refit 拆下 →
        // 立即切到载人模式并把切换器加回（切换器常驻，玩家通过反复拆它轮换模式）。
        if (!variant.hasHullMod(LENS_DUAL_MODE_CONFIG.switcherId)) {
            variant.activateDualMode(LENS_DUAL_MODE_CONFIG, LensArrayCoreHullModIds.MODE_CREWED, stats)
            variant.addMod(LENS_DUAL_MODE_CONFIG.switcherId)
            return
        }

        variant.hullSpec?.setShipSystemId(LensArrayCoreHullModIds.SYSTEM_AUTOMATED)

        // 蜂群思维：舰载机非导弹武器射程 +20%
        stats.fighterWingRange.modifyMult(id, FIGHTER_WING_RANGE_MULT)
        // 蜂群思维：导弹飞行速度/机动 +20%（最大速度、加速度、最大转速、转速加速度）
        stats.missileMaxSpeedBonus.modifyMult(id, MISSILE_SPEED_MULT)
        stats.missileAccelerationBonus.modifyMult(id, MISSILE_ACCEL_MULT)
        stats.missileMaxTurnRateBonus.modifyMult(id, MISSILE_TURN_RATE_MULT)
        stats.missileTurnAccelerationBonus.modifyMult(id, MISSILE_TURN_ACCEL_MULT)
        // 蜂群思维：最大航速 +20%
        stats.maxSpeed.modifyMult(id, MAX_SPEED_MULT)
    }

    override fun isApplicableToShip(ship: ShipAPI): Boolean = ship.isGravitationalLensShip()
    override fun showInRefitScreenModPickerFor(ship: ShipAPI): Boolean = false
}
