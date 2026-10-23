package cn.kasuminova.astd.combat.effect.arc.heavyionpulse

import cn.kasuminova.astd.api.buff.Buff
import cn.kasuminova.astd.api.buff.BuffLifetime
import cn.kasuminova.astd.impl.combat.CombatRandom
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.WeaponAPI

// WeaponAPI 没有 customData，逐武器随机序列只能存放在舰船侧的槽位复合键中。
class HeavyIonPulseShots(
    val seed: Long,
) : Buff {

    var callIndex: Int = 0

    constructor(source: ShipAPI, weapon: WeaponAPI) : this(CombatRandom.seedOf(source.id, weapon.slot.id))

    override val id: String get() = SHOTS_ID
    override val lifetime: BuffLifetime get() = BuffLifetime.HOST_BOUND

    override fun isHostValid(): Boolean = true

    companion object {
        const val SHOTS_ID = "astd_heavy_ion_pulse_shots"
    }
}
