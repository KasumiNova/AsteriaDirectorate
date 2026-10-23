package cn.kasuminova.astd.api.combat

import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.ShipAPI
import org.lwjgl.util.vector.Vector2f

/**
 * 一次锥状范围伤害结算的输入。几何筛选和伤害结算由结算器执行，命中检测与特效由调用方负责。
 */
data class ConeImpactSpec(
    /** 锥顶点（世界坐标，su）：命中点/引爆点。 */
    val origin: Vector2f,

    /** 锥中轴方向（命中矢量/飞行矢量）。允许非单位矢量：结算器记 WARN 后归一化，不静默产出错误锥形。 */
    val direction: Vector2f,

    /** 锥半角（度）：面板锥角/2，由难度锚点换算后传入；合法域 [0, 180]，越界 clamp 并记 WARN。 */
    val halfAngleDeg: Float,

    /** 锥长（su）：自顶点沿中轴的波及距离；非正属配置错误，记 WARN 且跳过结算。 */
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

    /**
     * 命中目标确定后调整实际伤害；null 时使用 [damage]。
     * 回调对每个目标返回相同的伤害值。
     */
    val damageAdjust: ConeDamageAdjust? = null,
)

/** 判断通过类型、归属与几何筛选的目标是否接受伤害。 */
fun interface ConeTargetFilter {
    /** 返回 true 时纳入结算。 */
    fun accept(target: CombatEntityAPI): Boolean
}

/** 根据最终命中目标列表调整每个目标受到的伤害。 */
fun interface ConeDamageAdjust {
    /** 返回实际伤害；负数或 NaN 由结算器按 0 处理并记录 WARN。 */
    fun adjust(hits: List<CombatEntityAPI>): Float
}
