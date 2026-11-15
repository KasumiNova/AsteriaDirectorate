package cn.kasuminova.astd.combat.hullmods.arc

import com.fs.starfarer.api.combat.ShipAPI

class ImaginaryCharge {

    var charge = 0f

    fun add(amount: Float) {
        charge = (charge + amount).coerceIn(0f, ImaginaryWingsTuning.CHARGE_MAX)
    }

    fun consumeFraction(fraction: Float): Float {
        val spent = charge * fraction
        charge -= spent
        return spent
    }

    fun tryExchangeSystemCharge(systemAmmo: Int): Boolean {
        if (systemAmmo != 0) return false
        if (charge <= ImaginaryWingsTuning.EXCHANGE_THRESHOLD) return false
        charge -= ImaginaryWingsTuning.EXCHANGE_COST
        return true
    }

    companion object {
        private const val KEY = "astd_imaginary_charge"

        // customData 首次写入必须调用 setCustomData；getCustomData 返回的空表不持久。
        fun of(ship: ShipAPI): ImaginaryCharge =
            ship.customData[KEY] as? ImaginaryCharge
                ?: ImaginaryCharge().also { ship.setCustomData(KEY, it) }

        fun peek(ship: ShipAPI): ImaginaryCharge? = ship.customData[KEY] as? ImaginaryCharge
    }
}
