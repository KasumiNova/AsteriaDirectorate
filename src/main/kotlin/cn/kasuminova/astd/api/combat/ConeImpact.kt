package cn.kasuminova.astd.api.combat

import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.ShipAPI
import org.lwjgl.util.vector.Vector2f

/**
 * 锥状冲击的一次性结算声明（规格 00-共享基建 §2.2）。
 *
 * 动机：正电子冲击波（自爆波及）、贯星之矛（OnHit 锥面）、摧锋（后续）三案共享同一套
 * 「输入一次引爆/命中事件 → 范围内目标清单 + 逐目标伤害结算」的几何与结算语义，
 * 参数表格化后由 `ConeImpactHandler` 统一执行，各案只声明差异（锥角/锥长/伤害类型/过滤策略）。
 *
 * 本对象只描述一次结算的输入，不含每帧近炸检测（调用方弹体脚本职责），也不含 VFX 树构建
 * （结算器返回命中清单后由调用方触发各自特效，锥面原型见 impl/render 的 ConeImpactVfx）。
 */
data class ConeImpactSpec(
    /** 锥顶点（世界坐标，su）：命中点/引爆点。 */
    val origin: Vector2f,

    /** 锥中轴方向（命中矢量/飞行矢量）。允许非单位矢量：结算器记 WARN 后归一化，不静默产出错误锥形。 */
    val direction: Vector2f,

    /** 锥半角（度）：面板锥角/2，由难度锚点换算后传入；合法域 [0, 180]，越界 clamp 并记 WARN。 */
    val halfAngleDeg: Float,

    /** 锥长（su）：自顶点沿中轴的波及距离；非正属配置错误，记 WARN 且本次不结算。 */
    val range: Float,

    /** 面板倍率折算后的基准伤害；负值/NaN 属配置错误，clamp 到 0 并记 WARN。 */
    val damage: Float,

    /** 伤害类型：正电子 FRAGMENTATION；贯星 FRAGMENTATION + 同锚点 ENERGY(EMP) 两发。 */
    val damageType: DamageType,

    /** 附带 EMP 伤害（贯星），0 表示无；负值/NaN clamp 到 0 并记 WARN。 */
    val empDamage: Float = 0f,

    /** 伤害来源（归功/AI 仇恨/浮字归属），可空。 */
    val source: ShipAPI?,

    /** 归属方（敌我过滤基准）：与目标 owner 相同者剔除，不波及友军。 */
    val owner: Int,

    /** 目标过滤策略：粗筛与几何精筛后的逐目标终判（如贯星豁免命中本体）。 */
    val filter: ConeTargetFilter,

    /** 是否结算舰船（默认 true）。 */
    val hitShips: Boolean = true,

    /** 是否结算战机（默认 true）。 */
    val hitFighters: Boolean = true,

    /** 是否结算导弹（默认 true）；非导弹的普通弹体永不纳入。 */
    val hitMissiles: Boolean = true,
)

/**
 * 锥状冲击目标过滤策略。
 *
 * 动机：三案对「谁该吃锥面伤害」的终判各不相同（正电子引爆判定严格排除舰船、贯星豁免命中本体），
 * 而类型/归属/hulk 等共性过滤已由结算器承担；本接口只承载各案的特异终判。
 */
fun interface ConeTargetFilter {
    /** 粗筛与几何精筛后逐目标终判；返回 true 纳入结算。 */
    fun accept(target: CombatEntityAPI): Boolean
}
