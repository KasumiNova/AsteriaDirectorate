package cn.kasuminova.astd.combat.lens.system

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.combat.lens.system.GravityRiftTuning.MINE_BASE_DAMAGE
import cn.kasuminova.astd.combat.lens.system.GravityRiftTuning.PHASE_SUPPRESS_COOLDOWN_REMAINING
import cn.kasuminova.astd.combat.lens.system.GravityRiftTuning.VORTEX_DURATION
import cn.kasuminova.astd.combat.lens.system.GravityRiftTuning.VORTEX_FADE_IN
import cn.kasuminova.astd.combat.lens.system.GravityRiftTuning.VORTEX_FADE_OUT
import com.fs.starfarer.api.plugins.ShipSystemStatsScript
import kotlin.math.sqrt

/**
 * 引力裂隙发生器（茑萝级舰船系统）的机制数值声明与纯函数
 * （purple/20-production.md §2，2026-09 D27 重做；2026-09-20 二轮重做：目标锁定 +
 * 贴图旋涡 + 真实光束）。
 *
 * 动机：系统需锁定目标激活，在目标舰随机位置（优先无护盾覆盖区域）产生贴图旋涡，
 * 1s 后自旋涡中心向目标发射**真实光束**（隐藏光束武器挂 FX drone，机制与攻击间隔
 * 直接复用原版裂隙洪流发射极 riftcascade：光束命中目标期间每 0.1s 沿目标周边弧线
 * 布一枚裂隙雷，(射程 − 命中长度)/200 + 1、上限 5）。系统持续期间（IN + ACTIVE + OUT）
 * 舰船获得全类型固定减伤，红色 jitter 结构对齐原版时流之壳；相位斗篷压制仅 IN/ACTIVE
 * 生效（OUT 解除，2026-10 细化）。数值集中在此声明，
 * 供系统脚本每帧实时解析（LunaLib 设置变更即时生效），并由单元测试直接驱动。
 *
 * 玩家来源（owner == 0）按我方档位取值（默认砺刃 v2，见 DifficultyTuning.valueFor）。
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

    /** 单裂隙伤害区间下限（v1 750 / v2 1000 / v5 1750，三锚点 LINEAR）。 */
    val RIFT_DAMAGE_MIN = ScalingEntry(750f, 1000f, 1750f)

    /** 单裂隙伤害区间上限（v1 1250 / v2 1500 / v5 2250，三锚点 LINEAR）。 */
    val RIFT_DAMAGE_MAX = ScalingEntry(1250f, 1500f, 2250f)

    /** 系统持续期间（IN + ACTIVE + OUT）舰船全类型固定减伤比例（v1 50% / v2 60% / v5 90%，三锚点 LINEAR）。 */
    val DAMAGE_TAKEN_REDUCTION = ScalingEntry(0.50f, 0.60f, 0.90f)

    /** 一次激活所需的全部机制数值（难度解析结果）。 */
    data class Values(
        /** 单裂隙伤害区间下限（首枚裂隙）。 */
        val damageMin: Float,
        /** 单裂隙伤害区间上限（末枚裂隙）。 */
        val damageMax: Float,
        /** 系统持续期间全类型减伤比例（写 stat 时经 [damageTakenMult] 换算）。 */
        val damageTakenReduction: Float,
    )

    /** 难度取值唯一入口：玩家阵营按我方档位（默认砺刃 v2）映射，其余阵营按轨一 k_s 映射。 */
    fun resolve(tuning: DifficultyTuning, isPlayer: Boolean): Values = Values(
        damageMin = tuning.valueFor(RIFT_DAMAGE_MIN, isPlayer),
        damageMax = tuning.valueFor(RIFT_DAMAGE_MAX, isPlayer),
        damageTakenReduction = tuning.valueFor(DAMAGE_TAKEN_REDUCTION, isPlayer),
    )

    /** 减伤比例换算为承伤乘区（纯函数）：1 − 减伤，钳制到 [0, 1]。 */
    fun damageTakenMult(reduction: Float): Float = (1f - reduction).coerceIn(0f, 1f)

    /**
     * 系统期间相位斗篷压制的目标剩余冷却（纯函数）：斗篷剩余冷却不足 [PHASE_SUPPRESS_COOLDOWN_REMAINING]
     * 时返回该值（需改写），已有更长的自然冷却时返回 null（不缩短既有冷却）。
     */
    fun phaseSuppressCooldown(cooldownRemaining: Float, inCooldown: Boolean): Float? =
        if (inCooldown && cooldownRemaining >= PHASE_SUPPRESS_COOLDOWN_REMAINING) null
        else PHASE_SUPPRESS_COOLDOWN_REMAINING

    /** 系统期间相位斗篷每帧钉住的剩余冷却（秒）：系统结束后斗篷在该余量内恢复可用。 */
    const val PHASE_SUPPRESS_COOLDOWN_REMAINING = 0.1f

    /**
     * 系统期间红色 jitter 的强度与描边外扩距离（纯函数，结构对齐原版时流之壳
     * [com.fs.starfarer.api.impl.combat.TemporalShellStats]）：
     * IN 按充能进度爬升并钳到 1，ACTIVE 满额，OUT 即 effectLevel 线性衰减；
     * 强度开平方（低段更亮），描边外扩 = 衰减前强度 × 10。
     * 返回 (jitterLevel, rangeBonus)。
     */
    fun jitterLevelAndRange(
        state: ShipSystemStatsScript.State,
        effectLevel: Float,
        chargeUpDur: Float,
    ): Pair<Float, Float> {
        var jitterLevel = effectLevel
        val rangeBonus: Float
        when (state) {
            ShipSystemStatsScript.State.IN -> {
                jitterLevel = (effectLevel / (1f / chargeUpDur)).coerceAtMost(1f)
                rangeBonus = jitterLevel * 10f
            }
            ShipSystemStatsScript.State.ACTIVE -> {
                jitterLevel = 1f
                rangeBonus = 10f
            }
            else -> {
                rangeBonus = effectLevel * 10f
            }
        }
        return sqrt(jitterLevel) to rangeBonus
    }

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
