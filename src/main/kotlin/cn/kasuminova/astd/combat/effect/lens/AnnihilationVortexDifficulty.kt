package cn.kasuminova.astd.combat.effect.lens

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.api.difficulty.ScalingMap
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl

/**
 * 湮灭涡旋的机制数值锚点与难度取值入口（规格 04 §2.2）。
 *
 * 动机：涡旋半径 / 坍缩 AOE 倍率 / 吸收阈值三条三锚点集中登记，玩家固定 v2 的取值口径
 * 与轨一 k_s 映射只有一处实现（对齐首批计划 §11「沉淀前各自实现」口径，与 01/03 组同型）。
 *
 * 数值缩放口径（90 计划全局约定）：敌方按轨一 k_s 三锚点线性映射；玩家来源（owner == 0）固定 v2。
 */
object AnnihilationVortexDifficulty {

    /** 涡旋半径（su）：迟暮 150 / 砺刃 187.5 / 破晓 300（设计案定稿三锚点，线性为设计裁定口径）。 */
    val RADIUS = ScalingEntry(150f, 187.5f, 300f, ScalingMap.LINEAR)

    /** 坍缩 AOE 伤害倍率：迟暮 0.5 / 砺刃 1.0 / 破晓 2.5（设计案定稿三锚点）。 */
    val AOE_MULT = ScalingEntry(0.5f, 1.0f, 2.5f, ScalingMap.LINEAR)

    /** 吸收阈值/软上限拐点（折算后池值）：迟暮 3200 / 砺刃 8800 / 破晓 16000（设计案定稿三锚点）。 */
    val ABSORB_LIMIT = ScalingEntry(3200f, 8800f, 16000f, ScalingMap.LINEAR)

    /** 超出阈值部分折算比（定稿提案值，目检可调）。 */
    const val EXCESS_RATIO = 0.25f

    /** 空爆保底（无缩放）：吞噬池低于该值按该值计入坍缩伤害。 */
    const val POOL_FLOOR = 500f

    /** 坍缩半径 = 涡旋半径 × 150%（不缩放，跟随半径本身）。 */
    const val COLLAPSE_RAD_MUL = 1.5f

    /** 涡旋边缘处指向中心的最大牵引加速度 su/s²（目检可调）。 */
    const val PULL_ACCEL_MAX = 1200f

    /** 吸收半径下限（su）；吸收半径 = max(本值, 涡旋半径 × [ABSORB_RADIUS_MUL])。 */
    const val ABSORB_RADIUS_MIN = 30f

    /** 吸收半径随涡旋半径的比例（规格 04 §2.2：radius × 0.25）。 */
    const val ABSORB_RADIUS_MUL = 0.25f

    /** 类型转换比：ENERGY 1.0 / HIGH_EXPLOSIVE 0.5 / KINETIC 0.5 / FRAGMENTATION 0.25（固定，不缩放）。 */
    val CONVERSION: Map<com.fs.starfarer.api.combat.DamageType, Float> = mapOf(
        com.fs.starfarer.api.combat.DamageType.ENERGY to 1.0f,
        com.fs.starfarer.api.combat.DamageType.HIGH_EXPLOSIVE to 0.5f,
        com.fs.starfarer.api.combat.DamageType.KINETIC to 0.5f,
        com.fs.starfarer.api.combat.DamageType.FRAGMENTATION to 0.25f,
    )

    /**
     * 难度统一取值：玩家来源（[sourceOwner] == 0）固定 v2，否则按轨一 k_s 映射。
     * 开火起点一次性调用并缓存本周期（规格 04 §2.2）。
     */
    fun resolve(entry: ScalingEntry, sourceOwner: Int): Float =
        resolve(DifficultyTuningImpl, entry, sourceOwner)

    /** 可注入 [DifficultyTuning] 的取值入口（单元测试与运行共用同一路径）。 */
    fun resolve(tuning: DifficultyTuning, entry: ScalingEntry, sourceOwner: Int): Float =
        if (sourceOwner == 0) entry.v2 else tuning.value(entry)

    /** 吸收半径 = max([ABSORB_RADIUS_MIN], 涡旋半径 × [ABSORB_RADIUS_MUL])；[radius] 先 clamp 到最小涡旋半径。 */
    fun absorbRadiusFor(radius: Float): Float =
        maxOf(ABSORB_RADIUS_MIN, radius.coerceAtLeast(ABSORB_RADIUS_MIN) * ABSORB_RADIUS_MUL)

    /** 坍缩伤害 = max(池值, [POOL_FLOOR]) × AOE 倍率（空池保底在此生效，规格 04 §2.4）。 */
    fun collapseDamage(poolTotal: Float, aoeMult: Float): Float =
        maxOf(poolTotal, POOL_FLOOR) * aoeMult

    /** 坍缩半径 = 涡旋半径 × [COLLAPSE_RAD_MUL]。 */
    fun collapseRadiusFor(radius: Float): Float = radius * COLLAPSE_RAD_MUL
}
