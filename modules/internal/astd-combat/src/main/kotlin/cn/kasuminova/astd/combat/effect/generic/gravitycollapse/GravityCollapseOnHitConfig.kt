package cn.kasuminova.astd.combat.effect.generic.gravitycollapse

import cn.kasuminova.astd.api.difficulty.ScalingEntry

/**
 * 引力坍缩炮：命中持续效果配置。
 *
 * 该类型被 [GravityCollapseOnHitHandler] 使用；单独放文件里以保证文件名与类名对应。
 *
 * 难度缩放数值以三锚点 [ScalingEntry] 登记（线性映射），运行时按来源舰归属解析：
 * 玩家来源按我方档位系数映射（默认砺刃 2.0，等价早期固定 v2 口径），敌方来源由固有缩放系数 k_s 派生。
 */
internal data class GravityCollapseOnHitConfig(
    /** tick 间隔（秒）。 */
    val tickInterval: Float = 0.5f,
    /** 额外 AOE 的最终生效半径（su）。 */
    val aoeRadius: Float = 200f,

    /** 是否要求 beam 有 damageTarget 才触发（旧行为：只有命中才触发）。 */
    val requireDamageTarget: Boolean = true,
    /** AOE 是否允许伤害友军/中立（新行为：会伤及友军与中立目标）。 */
    val affectAlliesAndNeutral: Boolean = false,
    /** AOE 是否影响非 Ship 实体（如陨石/残骸/导弹等）。 */
    val affectNonShips: Boolean = false,
    /** AOE 是否影响残骸（ShipAPI.isHulk）。 */
    val affectHulks: Boolean = false,

    /** 仅用于视觉：随武器尺寸缩放（不影响机制半径/伤害）。 */
    val vfxScale: Float = 1f,

    /** 穿甲打击强度倍率（打击强度 = 单次结算伤害 × 该倍率 × HE 对装甲类型倍率；固定口径，不随难度缩放）。 */
    val armorPierceDamageMult: Float = 2f,

    /** 范围高爆伤害比例（相对面板总伤害的 tick 折算值）三锚点。 */
    val aoeDamageRatio: ScalingEntry,
    /** 目标当前船体值附加伤害比例三锚点（每次坍缩 tick 追加，与脉冲合并为同一结算实例）。 */
    val hullDamageRatio: ScalingEntry,
    /** 最大航速与机动性降低比例三锚点（命中装甲/船体时施加）。 */
    val mobilityReduction: ScalingEntry,
    /** 机动抑制持续时间（秒）三锚点。 */
    val mobilityDuration: ScalingEntry,
    /** 对相位中舰船的伤害折算比例三锚点（相位舰仍截断光束，但按比例承受伤害）。 */
    val phasedDamageRatio: ScalingEntry,
)
