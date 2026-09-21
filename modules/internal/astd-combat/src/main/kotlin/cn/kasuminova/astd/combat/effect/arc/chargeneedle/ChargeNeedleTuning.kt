package cn.kasuminova.astd.combat.effect.arc.chargeneedle

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.api.difficulty.ScalingTable
import cn.kasuminova.astd.combat.effect.arc.chargeneedle.ChargeNeedleTuning.DECAY_FLOOR_PER_SECOND
import cn.kasuminova.astd.combat.effect.arc.chargeneedle.ChargeNeedleTuning.DECAY_RATIO_PER_SECOND
import cn.kasuminova.astd.combat.effect.arc.chargeneedle.ChargeNeedleTuning.DECAY_SHIELD_OFF_MULT
import cn.kasuminova.astd.combat.effect.arc.chargeneedle.ChargeNeedleTuning.DISSIPATION_CAP_MULT
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.ShipAPI
import kotlin.math.min

/**
 * 电荷针刺 / 重型电荷针刺的机制数值声明与安全闸纯函数（规格 01 §2.2 / §2.5，2026-09 机制修订）。
 *
 * 动机：两槽位机制完全同源（设计案：中槽变体机制完全复用无差异化），查表/锚点与固定常量
 * 集中在一处声明；泄放判定、体型固定软辐能查表与 200% 耗散上限折算均为纯函数，
 * 供 OnHit / Stacks 调用并由单元测试直接驱动。
 *
 * 数值缩放口径（2026-09 修订二）：泄放概率 / EMP 倍率为五档精确查表（[ScalingTable]）；
 * 每层维持加成 1%~5% 与体型固定软辐能（0.5/1/1.5/2 ~ 2.5/5/7.5/10）保持三锚点 LINEAR（逐档恰重合）；
 * 固定软辐能仅在目标**护盾开启期间**产出（Stacks 逐帧按 shield.isOn 门控）；
 * 消散速率（当前层数 4%/s、护盾关闭时翻倍 8%/s、下限 2 层/s）与 200% 耗散上限**不受难度系数影响**（用户裁定）。
 * 玩家来源（owner == 0）固定 v2（砺刃档）。
 */
object ChargeNeedleTuning {

    /** null 体型兜底的一次性 WARN 闸（配置异常必须可见，不刷屏）。 */
    @Volatile
    private var warnedNullHullSize = false

    private val log = Global.getLogger(ChargeNeedleTuning::class.java)

    /** 每层护盾维持加成（v1 1% / v2 2% / v5 5%；最终乘区，与五档表 1/2/3/4/5 逐档重合）。 */
    val PER_STACK = ScalingEntry(0.01f, 0.02f, 0.05f)

    /** 船体/装甲命中泄放概率（五档查表：k1 20% / k2 30% / k3 40% / k4 50% / k5 60%）。 */
    val DISCHARGE_CHANCE = ScalingTable(0.20f, 0.30f, 0.40f, 0.50f, 0.60f)

    /** 泄放 EMP 倍率（五档查表：k1 100% / k2 150% / k3 200% / k4 250% / k5 300%）。 */
    val DISCHARGE_EMP_MULT = ScalingTable(1.00f, 1.50f, 2.00f, 2.50f, 3.00f)

    /** 泄放基准 EMP（面板单发 EMP，固定不缩放）。 */
    const val BASE_DISCHARGE_EMP = 100f

    /** 每层额外固定软辐能产出（su/s，按目标舰体型）：护卫舰 v1 0.5 / v2 1.5 / v5 2.5。 */
    val FLAT_FLUX_FRIGATE = ScalingEntry(0.5f, 1.5f, 2.5f)

    /** 驱逐舰 v1 1 / v2 3 / v5 5。 */
    val FLAT_FLUX_DESTROYER = ScalingEntry(1f, 3f, 5f)

    /** 巡洋舰 v1 1.5 / v2 4.5 / v5 7.5。 */
    val FLAT_FLUX_CRUISER = ScalingEntry(1.5f, 4.5f, 7.5f)

    /** 主力舰 v1 2 / v2 6 / v5 10。 */
    val FLAT_FLUX_CAPITAL = ScalingEntry(2f, 6f, 10f)

    /** 层数绝对上限（固定不缩放；产出上限由 [DISSIPATION_CAP_MULT] 输出折算承担）。 */
    const val ABSOLUTE_MAX_STACKS = 200

    /** 连续消散比例（当前层数 × 该比例 层/s，固定不缩放，不受难度系数影响）。 */
    const val DECAY_RATIO_PER_SECOND = 0.04f

    /** 护盾关闭时的消散速率倍率（关盾翻倍 = 8%/s，固定不缩放）。 */
    const val DECAY_SHIELD_OFF_MULT = 2f

    /** 连续消散速率下限（层/s，固定不缩放，不受难度系数影响；开盾/关盾同一下限）。 */
    const val DECAY_FLOOR_PER_SECOND = 2f

    /** 产出上限：维持乘区额外量 + 固定软辐能之和 ≤ 目标最终耗散 × 该倍率（固定不缩放）。 */
    const val DISSIPATION_CAP_MULT = 2f

    /** 一次命中路由所需的全部机制数值（难度解析结果）。 */
    data class Values(
        /** 每层护盾维持加成（最终乘区）。 */
        val perStack: Float,
        /** 每层额外固定软辐能产出（su/s，按目标舰体型查表）。 */
        val flatFluxPerStack: Float,
        /** 船体/装甲命中泄放概率。 */
        val dischargeChance: Float,
        /** 泄放 EMP 倍率。 */
        val dischargeEmpMult: Float,
    )

    /**
     * 难度取值唯一入口：玩家来源固定 v2，否则按轨一 k_s 映射（查表项五档精确取档）。
     * 每次命中调用一次（不缓存），保证 LunaLib 设置变更即时生效。
     *
     * @param hullSize 目标舰体型（固定软辐能分档依据；null 按护卫舰档兜底并视为配置异常的上游遗漏）
     */
    fun resolve(tuning: DifficultyTuning, isPlayer: Boolean, hullSize: ShipAPI.HullSize?): Values = Values(
        perStack = if (isPlayer) PER_STACK.v2 else tuning.value(PER_STACK),
        flatFluxPerStack = flatFluxForSize(tuning, isPlayer, hullSize),
        dischargeChance = if (isPlayer) DISCHARGE_CHANCE.v2 else tuning.value(DISCHARGE_CHANCE),
        dischargeEmpMult = if (isPlayer) DISCHARGE_EMP_MULT.v2 else tuning.value(DISCHARGE_EMP_MULT),
    )

    /** 体型固定软辐能分档（纯函数）：护卫/驱逐/巡洋/主力四档；战机/DEFAULT 按护卫舰档（辐能池体量相当，
     * 设计裁定）；null 属配置异常的上游遗漏——按护卫舰档兜底并 WARN 一次（不静默）。 */
    fun flatFluxForSize(tuning: DifficultyTuning, isPlayer: Boolean, hullSize: ShipAPI.HullSize?): Float {
        val entry = when (hullSize) {
            ShipAPI.HullSize.DESTROYER -> FLAT_FLUX_DESTROYER
            ShipAPI.HullSize.CRUISER -> FLAT_FLUX_CRUISER
            ShipAPI.HullSize.CAPITAL_SHIP -> FLAT_FLUX_CAPITAL
            null -> {
                if (!warnedNullHullSize) {
                    warnedNullHullSize = true
                    log.warn("电荷针刺固定软辐能查表收到 null 体型（配置异常的上游遗漏），按护卫舰档兜底（一次性告警）")
                }
                FLAT_FLUX_FRIGATE
            }

            else -> FLAT_FLUX_FRIGATE
        }
        return if (isPlayer) entry.v2 else tuning.value(entry)
    }

    /**
     * 连续消散速率（纯函数）：当前层数 × [DECAY_RATIO_PER_SECOND]（护盾关闭时 ×[DECAY_SHIELD_OFF_MULT]），
     * 下限 [DECAY_FLOOR_PER_SECOND] 层/s。不受难度系数影响；[stacks] ≤ 0 时恒 0（无层可散）。
     */
    fun decayPerSecond(stacks: Float, shieldOn: Boolean): Float =
        if (stacks <= 0f) {
            0f
        } else {
            val ratio = DECAY_RATIO_PER_SECOND * (if (shieldOn) 1f else DECAY_SHIELD_OFF_MULT)
            maxOf(stacks * ratio, DECAY_FLOOR_PER_SECOND)
        }

    /**
     * 200% 耗散上限折算（纯函数，唯一入口）：维持乘区额外量 + 固定软辐能之和超过
     * [DISSIPATION_CAP_MULT] × 最终耗散时，两项按比例同步压缩（factor ∈ (0, 1]），
     * 不超上限恒 1.0 原样放行。
     *
     * 0 值防线（只定返回值语义；WARN 日志由调用侧按船去重承担，见 ChargeNeedleStacks）：
     * - [dissipation] ≤ 0（耗散被特殊机制压没的异常态）：返回 0——产出整体压没，禁止静默恒零；
     * - 两项之和 ≤ 0（层数为 0 或配置全零）：返回 1——无产出无需压缩。
     */
    fun dissipationCapFactor(dissipation: Float, upkeepExtra: Float, flatExtra: Float): Float {
        val total = upkeepExtra + flatExtra
        if (total <= 0f) return 1f
        if (dissipation <= 0f) return 0f
        return min(1f, DISSIPATION_CAP_MULT * dissipation / total)
    }

    /**
     * 泄放判定（纯函数）：严格小于口径——[roll] ∈ [0, 1)，chance = 0 恒不触发，
     * chance = 1 时 roll = 0.999 仍触发，roll == chance 边界不触发。
     */
    fun shouldDischarge(roll: Float, chance: Float): Boolean = roll < chance
}
