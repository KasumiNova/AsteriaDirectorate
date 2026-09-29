package cn.kasuminova.astd.combat.hullmods.affix

import com.fs.starfarer.api.combat.ShipAPI

/**
 * 词缀体系 memory 读取工具：
 * - 从 FleetMember memory 读取 k_p（0..1，轨二进程系数）与 totalMult（1..15）。
 *
 * **当前状态（2026-09 剧情重做后）**：赏金舰队生成器已移除，两个 memory key **暂无写入方**，
 * getK 恒 0、getTotalMult 恒 1，读取点（lens 系词条）按中性值运行。词缀体系代码保留，
 * 后续剧情重做落地新的挂载方时恢复写入。
 *
 * **取值约束**：k_p 是生成表专用系数——舰队规模与词缀选量在生成时已按其定案，
 * 进入战斗后机制数值禁止再从此取（机制数值走轨一 [cn.kasuminova.astd.api.difficulty.DifficultyTuning]）。
 */
internal object AffixUtil {

    // memory key 字面值固定（舰队生成侧按同名字面值写入；避免引 Kotlin 常量导致加载顺序问题）。
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
