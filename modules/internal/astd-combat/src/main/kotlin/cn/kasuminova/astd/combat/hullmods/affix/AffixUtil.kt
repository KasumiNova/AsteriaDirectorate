package cn.kasuminova.astd.combat.hullmods.affix

import com.fs.starfarer.api.combat.ShipAPI

/**
 * 赏金词缀 HullMod 共用工具：
 * - 从 FleetMember memory 读取 k_p（0..1，轨二进程系数）与 totalMult（1..15）。
 *
 * **取值约束**：k_p 是生成表专用系数——舰队规模与词缀选量在生成时已按其定案，
 * 进入战斗后机制数值禁止再从此取（机制数值走轨一 [cn.kasuminova.astd.api.difficulty.DifficultyTuning]）。
 * 现存读取点属于待迁移存量（P5 词条阶段统一处理），新代码不得新增 getK 调用。
 */
internal object AffixUtil {

    // 与 campaign/bounty/BountyKeys.kt 保持一致（避免引 Kotlin 常量导致加载顺序问题）。
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

    /** 相位舰船判定：相位限定词缀（调谐/降频/深潜器）的落舰约束。 */
    @JvmStatic
    fun isPhaseShip(ship: ShipAPI?): Boolean = ship?.hullSpec?.isPhase == true
}
