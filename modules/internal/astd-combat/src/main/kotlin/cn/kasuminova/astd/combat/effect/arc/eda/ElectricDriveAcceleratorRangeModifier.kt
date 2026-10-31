package cn.kasuminova.astd.combat.effect.arc.eda

import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.combat.listeners.WeaponRangeModifier

/**
 * 「电驱加速炮」净空加速射程加成的按武器限定通道。
 *
 * 原版 Weapon.getRange() 每次调用实时重算并咨询本监听器
 * （ShipStats.getWeaponRange → CombatListenerUtil.getWeaponRangeFlatMod），
 * 因此直接按当前辐能水平求值即可随辐能动态伸缩；
 * 同时天然把加成限定在同类武器（astd_electric_drive_accelerator）上，
 * 不再污染舰体 ballisticWeaponRangeBonus 这一全舰实弹共享乘区，也不存在 modifier 残留问题。
 *
 * 无状态，全战斗共享同一实例；由 [ElectricDriveAcceleratorWeaponEffect] 按舰注册。
 */
class ElectricDriveAcceleratorRangeModifier : WeaponRangeModifier {

    override fun getWeaponRangePercentMod(ship: ShipAPI, weapon: WeaponAPI): Float = 0f

    override fun getWeaponRangeMultMod(ship: ShipAPI, weapon: WeaponAPI): Float = 1f

    override fun getWeaponRangeFlatMod(ship: ShipAPI, weapon: WeaponAPI): Float {
        if (ship.isHulk) return 0f
        if (weapon.spec?.weaponId != ElectricDriveChargeState.WEAPON_ID) return 0f
        val level = ship.fluxLevel
        if (level.isNaN()) return 0f
        return ElectricDriveAcceleratorDifficulty.rangeBonus(DifficultyTuningImpl, ship.owner, level)
    }
}
