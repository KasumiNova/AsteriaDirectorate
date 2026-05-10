package cn.kasuminova.astd.combat.effect.generic.gravitycollapse

/**
 * 引力坍缩炮：命中持续效果配置。
 *
 * 该类型被 [GravityCollapseOnHitHandler] 使用；单独放文件里以保证文件名与类名对应。
 */
internal data class GravityCollapseOnHitConfig(
    /** tick 间隔（秒）。 */
    val tickInterval: Float = 0.5f,
    /** 额外 AOE 半径基准（su）。 */
    val aoeRadiusBase: Float = 190f,
    /** AOE 半径：intensity=0 时的倍率。 */
    val aoeRadiusIntensityMinMul: Float = 0.75f,
    /** AOE 半径：intensity=1 时的倍率。 */
    val aoeRadiusIntensityMaxMul: Float = 1.15f,
    /** AOE 伤害衰减：边缘倍率（中心=1.0）。 */
    val aoeEdgeDamageMul: Float = 0.5f,

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

    /**
     * （已弃用）旧版“引力撕裂：额外撕裂装甲”的倍率（相对本次 AOE 伤害）。
     * 当前实现已移除“额外扣装甲”，仅保留低装甲时的贯穿船体伤害。
     */
    val tearArmorFraction: Float = 0.5f,
    /** 引力撕裂：低于该装甲比例才允许“穿透扣船体”。 */
    val tearArmorThreshold: Float = 0.50f,
    /** “斩杀”用的小额伤害：目标已濒死（船体<=1）时用于结算死亡链路。 */
    val executeDamage: Float = 100f,
)
