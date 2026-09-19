package cn.kasuminova.astd.combat.lens.system

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry

/**
 * 引力裂隙发生器（茑萝级舰船系统）的机制数值声明与纯函数
 * （purple/20-production.md §2，2026-09 D27 重做）。
 *
 * 动机：裂隙数量随开火距离递减（越近越多，最多 5 个）、单裂隙伤害按难度三锚点
 * 给区间并随裂隙序位插值递增——两套口径与原版裂隙洪流发射极（riftcascade）
 * 「(range − dist)/200 + 1、尺寸 0.75→1.25 梯度」同构。数值集中在此声明，
 * 供系统脚本每帧实时解析（LunaLib 设置变更即时生效），并由单元测试直接驱动。
 *
 * 玩家来源（owner == 0）固定 v2（砺刃档）。
 */
object GravityRiftTuning {

    /** 系统射程（su）：指定落点距母舰超过该值时按该值限幅。 */
    const val SYSTEM_RANGE = 900f

    /** 裂隙数量上限。 */
    const val MAX_RIFTS = 5

    /** 每靠近该距离（su）多产生 1 个裂隙（对齐原版 UNUSED_RANGE_PER_SPAWN=200）。 */
    const val RANGE_PER_EXTRA_RIFT = 200f

    /** 裂隙逐个生成间隔（秒，对齐原版 SPAWN_INTERVAL≈0.1）。 */
    const val SPAWN_INTERVAL = 0.12f

    /**
     * 裂隙散布半径（su）：首个裂隙落在指定点，其余在以指定点为圆心的该半径内随机散布。
     */
    const val SCATTER_RADIUS = 120f

    /** 裂隙基础伤害（与 weapon_data 中 astd_grav_rift_minelayer 的 damage/shot 对齐）。 */
    const val MINE_BASE_DAMAGE = 1000f

    /** 单裂隙伤害区间下限（v1 600 / v2 800 / v5 1200，三锚点 LINEAR）。 */
    val RIFT_DAMAGE_MIN = ScalingEntry(600f, 800f, 1200f)

    /** 单裂隙伤害区间上限（v1 1000 / v2 1400 / v5 2000，三锚点 LINEAR）。 */
    val RIFT_DAMAGE_MAX = ScalingEntry(1000f, 1400f, 2000f)

    /** 一次激活所需的全部机制数值（难度解析结果）。 */
    data class Values(
        /** 单裂隙伤害区间下限（首个裂隙）。 */
        val damageMin: Float,
        /** 单裂隙伤害区间上限（末个裂隙）。 */
        val damageMax: Float,
    )

    /** 难度取值唯一入口：玩家固定 v2，否则按轨一 k_s 映射。 */
    fun resolve(tuning: DifficultyTuning, isPlayer: Boolean): Values = Values(
        damageMin = if (isPlayer) RIFT_DAMAGE_MIN.v2 else tuning.value(RIFT_DAMAGE_MIN),
        damageMax = if (isPlayer) RIFT_DAMAGE_MAX.v2 else tuning.value(RIFT_DAMAGE_MAX),
    )

    /**
     * 裂隙数量（纯函数）：(射程 − 开火距离) / 200 + 1，钳制到 [1, MAX_RIFTS]
     * （对齐原版裂隙洪流的数量公式）。
     */
    fun riftCount(distance: Float): Int =
        (((SYSTEM_RANGE - distance) / RANGE_PER_EXTRA_RIFT).toInt() + 1).coerceIn(1, MAX_RIFTS)

    /**
     * 第 [index] 个（0 起）裂隙的伤害（纯函数）：在 [damageMin, damageMax] 上按序位线性插值，
     * 首个取下限、末个取上限；仅 1 个时取上限。
     */
    fun riftDamage(damageMin: Float, damageMax: Float, index: Int, total: Int): Float =
        if (total <= 1) damageMax else damageMin + (damageMax - damageMin) * index / (total - 1)

    /** 裂隙伤害换算为地雷伤害乘区（纯函数）：伤害 / [MINE_BASE_DAMAGE]。 */
    fun riftDamageMult(damage: Float): Float = damage / MINE_BASE_DAMAGE
}
