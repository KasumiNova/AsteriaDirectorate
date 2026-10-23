package cn.kasuminova.astd.combat.effect.arc.eda

import cn.kasuminova.astd.api.buff.Buff
import cn.kasuminova.astd.api.buff.BuffLifetime
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.WeaponAPI

// WeaponAPI 没有 customData，逐武器随机序列只能存放在舰船侧的槽位复合键中。
class ElectricDriveChargeState(
    private val ship: ShipAPI,
    private val weapon: WeaponAPI,
    val seed: Long,
) : Buff {

    var callIndex: Int = 0
        private set

    fun nextCallIndex(): Int = callIndex++

    override val id: String get() = BUFF_ID
    override val lifetime: BuffLifetime get() = BuffLifetime.HOST_BOUND

    override fun isHostValid(): Boolean =
        ship.isAlive && !ship.isHulk && weapon.spec?.weaponId == WEAPON_ID

    companion object {
        const val BUFF_ID = "astd_eda_charge_state"

        const val WEAPON_ID = "astd_electric_drive_accelerator"
    }
}
