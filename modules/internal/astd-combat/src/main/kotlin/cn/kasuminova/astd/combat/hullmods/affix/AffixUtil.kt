package cn.kasuminova.astd.combat.hullmods.affix

import com.fs.starfarer.api.combat.ShipAPI

internal object AffixUtil {

    // 舰队生成侧按相同字面值写入，避免 Kotlin 常量引起加载顺序问题。
    const val MEM_K: String = "\$astd_bounty_k"
    const val MEM_TOTAL_MULT: String = "\$astd_bounty_total_mult"

    @JvmStatic
    fun getK(ship: ShipAPI?): Float {
        try {
            val fleet = ship?.fleetMember?.fleetData?.fleet ?: return 0f
            return clamp01(fleet.memoryWithoutUpdate.getFloat(MEM_K))
        } catch (_: Throwable) {
            return 0f
        }
    }

    @JvmStatic
    fun getTotalMult(ship: ShipAPI?): Float {
        try {
            val fleet = ship?.fleetMember?.fleetData?.fleet ?: return 1f
            val v = fleet.memoryWithoutUpdate.getFloat(MEM_TOTAL_MULT)
            return (if (v <= 0f) 1f else v).coerceIn(1f, 15f)
        } catch (_: Throwable) {
            return 1f
        }
    }

    @JvmStatic
    fun clamp01(v: Float): Float = v.coerceIn(0f, 1f)
}
