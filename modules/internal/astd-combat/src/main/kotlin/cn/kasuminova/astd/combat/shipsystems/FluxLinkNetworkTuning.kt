package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry

/**
 * 「辐能链接网络」（astd_arc_shared_flux_network，搭载舰：烽燧）调参。
 *
 * 机制：激活期间链接范围内的友舰，以自身辐能耗散的一定比例无条件将友舰辐能传输到自身；
 * 传输效率随距离衰减（25% 范围内 100%，75% 范围外 25%）；
 * 全链路总传输速率不超过自身最终辐能耗散的 [TOTAL_RATE_CAP] 倍，超额时优先传输辐能水平最高的友舰。
 */
object FluxLinkNetworkTuning {

    const val SYSTEM_ID = "astd_arc_shared_flux_network"

    /** 链接范围（su），受舰船系统射程 stat 影响。 */
    val RANGE = ScalingEntry(1000f, 1200f, 1800f)

    /** 传输速率：每秒以自身辐能耗散的该比例从友舰抽取辐能。 */
    val TRANSFER_RATE = ScalingEntry(0.20f, 0.25f, 0.40f)

    /** 全链路总传输速率上限：自身最终辐能耗散的该倍率，超额时优先辐能水平最高的友舰。 */
    const val TOTAL_RATE_CAP = 1.5f

    /** 硬辐能传输倍率：友舰硬辐能部分按 传输速率 × 该比例 传输。 */
    val HARD_TRANSFER_MULT = ScalingEntry(0.20f, 0.25f, 0.40f)

    /** 自身辐能耗散速率提升。 */
    val DISSIPATION_BONUS = ScalingEntry(0.30f, 0.40f, 0.70f)

    /** 自身硬辐能耗散比例提升（modifyFlat，值为占比）。 */
    val HARD_DISSIPATION_BONUS = ScalingEntry(0.15f, 0.20f, 0.35f)

    /** 武器射速/最大航速/机动性乘区（固定 -50%，不随难度缩放）。 */
    const val PENALTY_MULT = 0.50f

    /** 满效率距离比例：小于该范围比例时传输效率 100%。 */
    const val FULL_EFFICIENCY_RANGE_FRACTION = 0.25f

    /** 最低效率距离比例：大于该范围比例时传输效率降至 [MIN_EFFICIENCY]。 */
    const val MIN_EFFICIENCY_RANGE_FRACTION = 0.75f

    /** 最低传输效率。 */
    const val MIN_EFFICIENCY = 0.25f

    /** 自身辐能水平高于该值时系统自动关闭（防止回灌过载）。 */
    const val AUTO_DEACTIVATE_FLUX_LEVEL = 0.90f

    /** 链路光束渲染的最低平滑强度（占满效率速率的比例），低于则隐藏链路。 */
    const val LINK_VISIBLE_INTENSITY = 0.03f

    data class Values(
        val range: Float,
        val transferRate: Float,
        val hardTransferMult: Float,
        val dissipationBonus: Float,
        val hardDissipationBonus: Float,
    )

    fun resolve(tuning: DifficultyTuning, isPlayer: Boolean): Values = Values(
        range = tuning.valueFor(RANGE, isPlayer),
        transferRate = tuning.valueFor(TRANSFER_RATE, isPlayer),
        hardTransferMult = tuning.valueFor(HARD_TRANSFER_MULT, isPlayer),
        dissipationBonus = tuning.valueFor(DISSIPATION_BONUS, isPlayer),
        hardDissipationBonus = tuning.valueFor(HARD_DISSIPATION_BONUS, isPlayer),
    )

    /** 距离效率：25% 范围内 100%，75% 范围外 25%，中间线性过渡。 */
    fun efficiencyOf(distanceFraction: Float): Float {
        val f = distanceFraction.coerceIn(0f, 1f)
        if (f <= FULL_EFFICIENCY_RANGE_FRACTION) return 1f
        if (f >= MIN_EFFICIENCY_RANGE_FRACTION) return MIN_EFFICIENCY
        val t = (f - FULL_EFFICIENCY_RANGE_FRACTION) / (MIN_EFFICIENCY_RANGE_FRACTION - FULL_EFFICIENCY_RANGE_FRACTION)
        return 1f - t * (1f - MIN_EFFICIENCY)
    }
}
