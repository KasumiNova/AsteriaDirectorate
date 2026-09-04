package cn.kasuminova.astd.combat.shipsystems

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.ShipAPI

/** 断熵弧刃战斗状态：脉冲充能、激变窗口与虚粒子窗口。 */
object ASTDNegentropyEdgeState {
    const val HULL_ID = "astd_negentropy_edge"
    const val SPC3_WEAPON_ID = "astd_spc3"

    private const val CHARGE_KEY = "astd_negentropy_edge_charge"
    private const val DISPLAY_CHARGE_KEY = "astd_negentropy_edge_display_charge"
    private const val WINDOW_KEY = "astd_negentropy_edge_collapse_window"
    private const val THRESHOLD_HALF_KEY = "astd_negentropy_edge_threshold_half"
    private const val LAST_SHIFT_FROM_KEY = "astd_negentropy_edge_last_shift_from"

    fun isNegentropyEdge(ship: ShipAPI?): Boolean = ship?.hullSpec?.hullId == HULL_ID

    fun getCharge(ship: ShipAPI): Float = (ship.customData[CHARGE_KEY] as? Float ?: 0f).coerceIn(0f, 1f)

    fun getDisplayCharge(ship: ShipAPI): Float = (ship.customData[DISPLAY_CHARGE_KEY] as? Float ?: getCharge(ship)).coerceIn(0f, 1f)

    fun setDisplayCharge(ship: ShipAPI, value: Float) {
        ship.setCustomData(DISPLAY_CHARGE_KEY, value.coerceIn(0f, 1f))
    }

    fun setCharge(ship: ShipAPI, value: Float) {
        val next = value.coerceIn(0f, 1f)
        ship.setCustomData(CHARGE_KEY, next)
        if ((ship.customData[WINDOW_KEY] as? Float ?: 0f) <= 0f) setDisplayCharge(ship, next)
    }

    fun addCharge(ship: ShipAPI, amount: Float): Float {
        val next = (getCharge(ship) + amount).coerceIn(0f, 1f)
        setCharge(ship, next)
        return next
    }

    fun consumeCharge(ship: ShipAPI): Float {
        val value = getCharge(ship)
        ship.setCustomData(CHARGE_KEY, 0f)
        setDisplayCharge(ship, value)
        return value
    }

    fun decayCharge(ship: ShipAPI, amount: Float) {
        if (amount <= 0f) return
        setCharge(ship, getCharge(ship) - amount)
    }

    fun setCollapseWindow(ship: ShipAPI, seconds: Float) {
        ship.setCustomData(WINDOW_KEY, seconds.coerceAtLeast(0f))
        ship.setCustomData(THRESHOLD_HALF_KEY, seconds.coerceAtLeast(0f))
    }

    fun advanceWindows(ship: ShipAPI, amount: Float) {
        if (amount <= 0f) return
        val window = (ship.customData[WINDOW_KEY] as? Float ?: 0f) - amount
        val threshold = (ship.customData[THRESHOLD_HALF_KEY] as? Float ?: 0f) - amount
        if (window > 0f) ship.setCustomData(WINDOW_KEY, window) else ship.removeCustomData(WINDOW_KEY)
        if (threshold > 0f) ship.setCustomData(THRESHOLD_HALF_KEY, threshold) else ship.removeCustomData(THRESHOLD_HALF_KEY)
        val display = getDisplayCharge(ship)
        val charge = getCharge(ship)
        val dropRate = if (window > 0f) 0.55f else 1.25f
        val nextDisplay = if (display > charge) (display - dropRate * amount).coerceAtLeast(charge) else charge
        setDisplayCharge(ship, nextDisplay)
    }

    fun getCollapseWindowLevel(ship: ShipAPI): Float = ((ship.customData[WINDOW_KEY] as? Float ?: 0f) / 4f).coerceIn(0f, 1f)

    fun isThresholdHalved(ship: ShipAPI): Boolean = (ship.customData[THRESHOLD_HALF_KEY] as? Float ?: 0f) > 0f

    fun markShiftOrigin(ship: ShipAPI) {
        val now = Global.getCombatEngine()?.getTotalElapsedTime(false) ?: 0f
        ship.setCustomData(LAST_SHIFT_FROM_KEY, now)
    }

    fun lastShiftAge(ship: ShipAPI): Float {
        val now = Global.getCombatEngine()?.getTotalElapsedTime(false) ?: return Float.MAX_VALUE
        val from = ship.customData[LAST_SHIFT_FROM_KEY] as? Float ?: return Float.MAX_VALUE
        return now - from
    }
}