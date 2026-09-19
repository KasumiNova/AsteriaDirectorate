package cn.kasuminova.astd.combat.lens.system

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry

/**
 * 引力裂隙发生器（茑萝级舰船系统）的机制数值声明与纯函数
 * （purple/20-production.md §2，2026-09 D27 重做；2026-09-20 二轮重做：目标锁定 +
 * 贴图旋涡 + 真实光束）。
 *
 * 动机：系统需锁定目标激活，在目标舰随机位置（优先无护盾覆盖区域）产生贴图旋涡，
 * 1s 后自旋涡中心向目标发射**真实光束**（隐藏光束武器挂 FX drone，机制与攻击间隔
 * 直接复用原版裂隙洪流发射极 riftcascade：光束命中目标期间每 0.1s 沿目标周边弧线
 * 布一枚裂隙雷，(射程 − 命中长度)/200 + 1、上限 5）。数值集中在此声明，
 * 供系统脚本每帧实时解析（LunaLib 设置变更即时生效），并由单元测试直接驱动。
 *
 * 玩家来源（owner == 0）固定 v2（砺刃档）。
 */
object GravityRiftTuning {

    /** 系统基础射程（su）：激活时目标距母舰超过该值（经 systemRangeBonus 折算）不可施放。 */
    const val SYSTEM_RANGE = 1000f

    /** 光束基础射程（su）：隐藏光束武器面板射程，受能量武器与光束武器射程加成折算。 */
    const val BEAM_RANGE = 1000f

    /** 裂隙数量上限（对齐原版 MAX_RIFTS）。 */
    const val MAX_RIFTS = 5

    /** 光束每余下该未用射程（su）多布 1 枚裂隙（对齐原版 UNUSED_RANGE_PER_SPAWN=200）。 */
    const val RANGE_PER_EXTRA_RIFT = 200f

    /** 裂隙布雷间隔（秒，对齐原版 SPAWN_INTERVAL=0.1）。 */
    const val SPAWN_INTERVAL = 0.1f

    /** 裂隙沿目标周边弧线的布点间距（su，对齐原版 SPAWN_SPACING=175）。 */
    const val SPAWN_SPACING = 175f

    /** 旋涡自旋角速度（度/秒）。 */
    const val VORTEX_SPIN_DEG_PER_SEC = 60f

    /** 旋涡总时长（秒）= 淡入 [VORTEX_FADE_IN] + 全亮 + 淡出 [VORTEX_FADE_OUT]。 */
    const val VORTEX_DURATION = 3f
    const val VORTEX_FADE_IN = 1f
    const val VORTEX_FADE_OUT = 1f

    /** 光束发射延迟（秒）：激活后旋涡先自旋该时长再发射（= 系统 chargeUp）。 */
    const val BEAM_DELAY = 1f

    /** 光束持续时长（秒）：ACTIVE 1s + DOWN 1s，收口由系统脚本停止强火 + chargedown 淡出承担。 */
    const val BEAM_DURATION = 2f

    /** 旋涡贴图基准半径（su）：按目标碰撞半径比例缩放后钳制到该区间。 */
    const val VORTEX_RADIUS_FRAC = 0.75f
    const val VORTEX_RADIUS_MIN = 90f
    const val VORTEX_RADIUS_MAX = 260f

    /** 裂隙基础伤害（与 weapon_data 中 astd_grav_rift_minelayer 的 damage/shot 对齐）。 */
    const val MINE_BASE_DAMAGE = 1000f

    /** 单裂隙伤害区间下限（v1 600 / v2 800 / v5 1200，三锚点 LINEAR）。 */
    val RIFT_DAMAGE_MIN = ScalingEntry(600f, 800f, 1200f)

    /** 单裂隙伤害区间上限（v1 1000 / v2 1400 / v5 2000，三锚点 LINEAR）。 */
    val RIFT_DAMAGE_MAX = ScalingEntry(1000f, 1400f, 2000f)

    /** 一次激活所需的全部机制数值（难度解析结果）。 */
    data class Values(
        /** 单裂隙伤害区间下限（首枚裂隙）。 */
        val damageMin: Float,
        /** 单裂隙伤害区间上限（末枚裂隙）。 */
        val damageMax: Float,
    )

    /** 难度取值唯一入口：玩家固定 v2，否则按轨一 k_s 映射。 */
    fun resolve(tuning: DifficultyTuning, isPlayer: Boolean): Values = Values(
        damageMin = if (isPlayer) RIFT_DAMAGE_MIN.v2 else tuning.value(RIFT_DAMAGE_MIN),
        damageMax = if (isPlayer) RIFT_DAMAGE_MAX.v2 else tuning.value(RIFT_DAMAGE_MAX),
    )

    /**
     * 裂隙数量（纯函数）：(光束射程 − 光束当前命中长度) / 200 + 1，钳制到 [1, MAX_RIFTS]
     * （对齐原版裂隙洪流发射极的数量公式）。
     */
    fun riftCount(unusedRange: Float): Int =
        ((unusedRange / RANGE_PER_EXTRA_RIFT).toInt() + 1).coerceIn(1, MAX_RIFTS)

    /**
     * 第 [index] 枚（0 起）裂隙的伤害（纯函数）：在 [damageMin, damageMax] 上按序位线性插值，
     * 首枚取下限、末枚取上限；仅 1 枚时取上限。
     */
    fun riftDamage(damageMin: Float, damageMax: Float, index: Int, total: Int): Float =
        if (total <= 1) damageMax else damageMin + (damageMax - damageMin) * index / (total - 1)

    /** 裂隙伤害换算为地雷伤害乘区（纯函数）：伤害 / [MINE_BASE_DAMAGE]。 */
    fun riftDamageMult(damage: Float): Float = damage / MINE_BASE_DAMAGE

    /**
     * 旋涡 alpha 包络（纯函数）：[elapsed] 时刻的透明度——淡入 1s、全亮、淡出 1s，
     * 总时长 [VORTEX_DURATION]；[forceFadeOutAt] 非负时自该时刻起 1s 内压暗到 0
     * （系统被提前打断的收口路径）。
     */
    fun vortexAlpha(elapsed: Float, forceFadeOutAt: Float = -1f): Float {
        if (elapsed <= 0f) return 0f
        val envelope = (elapsed / VORTEX_FADE_IN).coerceIn(0f, 1f) *
            ((VORTEX_DURATION - elapsed) / VORTEX_FADE_OUT).coerceIn(0f, 1f)
        if (forceFadeOutAt < 0f || elapsed < forceFadeOutAt) return envelope
        val fade = (1f - (elapsed - forceFadeOutAt) / VORTEX_FADE_OUT).coerceIn(0f, 1f)
        return minOf(envelope, fade)
    }
}
