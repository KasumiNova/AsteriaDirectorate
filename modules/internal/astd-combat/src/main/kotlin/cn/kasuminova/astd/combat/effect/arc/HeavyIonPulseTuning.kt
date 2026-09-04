package cn.kasuminova.astd.combat.effect.arc

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry

/**
 * 重型离子脉冲的机制数值声明与纯函数（规格 02 §2.2 / §2.5）。
 *
 * 动机：泄放 EMP 电弧（船体/装甲命中概率触发）与 EMP 贯穿补伤（破晓敌版逐项解锁）
 * 的三锚点与固定常量集中在一处声明；泄放判定与贯穿补伤量均为纯函数，
 * 供 OnHit 调用并由单元测试直接驱动。
 *
 * 数值缩放口径（90 计划全局约定）：敌方按轨一 k_s 三锚点 LINEAR 映射；玩家来源（owner == 0）固定 v2。
 * EMP 贯穿不入 ScalingEntry——激活条件为 `fixedScale >= 5f && !isPlayer`（破晓敌版限定，
 * 玩家版本 owner == 0 固定 v2 口径天然排除：玩家永远不会获得此特效）。
 */
object HeavyIonPulseTuning {

    /** 泄放电弧触发概率（v1 25% / v2 31.25% / v5 50%）。 */
    val DISCHARGE_CHANCE = ScalingEntry(0.25f, 0.3125f, 0.50f)

    /** 泄放 EMP 倍率（v1 100% / v2 125% / v5 200%）。 */
    val DISCHARGE_EMP_MULT = ScalingEntry(1.00f, 1.25f, 2.00f)

    /** 泄放基准 EMP（单发等值面板 EMP，固定不缩放）。 */
    const val BASE_DISCHARGE_EMP = 600f

    /** EMP 贯穿减免下限：目标 EMP 减免超过 90%（mult < 0.1）时触发补伤（固定不缩放）。 */
    const val PIERCE_FLOOR = 0.1f

    /** EMP 贯穿折算补偿的除数下限（2026-07-29 A9 裁定方案 a）：mult 低于该值按该值折算，防补偿量爆炸。 */
    const val PIERCE_COMPENSATION_FLOOR = 0.01f

    /** EMP 贯穿解锁的固有缩放系数下限（破晓档 k_s = 5，逐项映射式解锁）。 */
    const val PIERCE_MIN_SCALE = 5f

    /** 一次命中路由所需的全部机制数值（难度解析结果）。 */
    data class Values(
        /** 泄放电弧触发概率。 */
        val dischargeChance: Float,
        /** 泄放 EMP 倍率。 */
        val dischargeEmpMult: Float,
        /** 来源是否为玩家（owner == 0）：贯穿激活判定与玩家固定 v2 口径的身份依据。 */
        val isPlayer: Boolean,
    )

    /**
     * 难度取值唯一入口：玩家来源固定 v2，否则按轨一 k_s 三锚点映射。
     * 每次命中调用一次（不缓存），保证 LunaLib 设置变更即时生效。
     */
    fun resolve(tuning: DifficultyTuning, isPlayer: Boolean): Values = Values(
        dischargeChance = if (isPlayer) DISCHARGE_CHANCE.v2 else tuning.value(DISCHARGE_CHANCE),
        dischargeEmpMult = if (isPlayer) DISCHARGE_EMP_MULT.v2 else tuning.value(DISCHARGE_EMP_MULT),
        isPlayer = isPlayer,
    )

    /**
     * EMP 贯穿激活判定：破晓敌版限定（玩家固定 v2 口径天然排除）。
     */
    fun pierceActive(isPlayer: Boolean, fixedScale: Float): Boolean = !isPlayer && fixedScale >= PIERCE_MIN_SCALE

    /**
     * 泄放判定（纯函数）：严格小于口径——[roll] ∈ [0, 1)，chance = 0 恒不触发，
     * roll == chance 边界不触发（与 01 电荷针刺一致）。
     */
    fun shouldDischarge(roll: Float, chance: Float): Boolean = roll < chance

    /**
     * EMP 贯穿补伤量（纯函数，设计案定稿口径）：empDamage × (0.1 - mult) / 0.1。
     *
     * 0 值防线：除数为编译期常量 [PIERCE_FLOOR]，无除零路径；
     * [mult] = 0（目标 EMP 免疫 100%）时公式自然退化为 [emp] × 1.0（追加整发等值 EMP），不静默恒零；
     * [mult] 恰等于 [PIERCE_FLOOR] 不补（`<` 口径，测试钉死）。
     */
    fun empPierceExtra(emp: Float, mult: Float): Float =
        if (mult >= PIERCE_FLOOR) 0f else emp * (PIERCE_FLOOR - mult) / PIERCE_FLOOR

    /**
     * EMP 贯穿实际施加量（2026-07-29 A9 裁定方案 a，折算补偿）：extra / max(mult, [PIERCE_COMPENSATION_FLOOR])。
     *
     * 动机：`applyDamage` 的 empDamage 会被目标 `empDamageTakenMult` 再乘一次——直接施加 extra
     * 会让高抗性目标实际结算 ≈ extra × mult ≈ 0，与浮字显示脱节（A9 实机证实）。
     * 折算后引擎二次乘算恰好回补到 extra（mult ≥ 0.01 时精确；0 < mult < 0.01 按 0.01 折算，
     * 少量欠补属防爆炸钳制）。mult ≤ 0（目标完全 EMP 免疫）返回 0：补偿无法突破 0 乘区，
     * 由调用侧整体跳过——不施加、不弹与实际结算脱节的浮字。
     */
    fun empPierceApplied(extra: Float, mult: Float): Float =
        if (mult <= 0f) 0f else extra / maxOf(mult, PIERCE_COMPENSATION_FLOOR)
}
