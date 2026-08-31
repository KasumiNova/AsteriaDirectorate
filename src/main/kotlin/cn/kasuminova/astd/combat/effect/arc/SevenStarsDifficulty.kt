package cn.kasuminova.astd.combat.effect.arc

import cn.kasuminova.astd.api.difficulty.ScalingEntry
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.ShipAPI

/**
 * “七星”折跃发射器的机制数值锚点与发射时刻快照（规格 07 §2.2）。
 *
 * 动机：首发倍率/每跳提升/累计上限三锚点集中登记在一处；难度取值调用点唯一——
 * 发射时刻由 [SevenStarsOnFireEffect] 一次性 [snapshot]，同一发弹体生命周期内恒定
 * （战斗中途改 LunaLib 设置不影响在飞弹体），连跳脚本与单元测试直接驱动本对象。
 *
 * 数值缩放口径（90 计划全局约定）：敌方/友军 AI 按轨一 k_s 三锚点映射；
 * 玩家来源（owner == 0）固定 v2（对照 ASTDVirtualParticleLatticeWebHullMod 既有口径）。
 * 多段终结为破晓（k_s = 5）敌版限定（设计案 2026-07-28 微调）：玩家恒单段。
 */
object SevenStarsDifficulty {
    private val log = Global.getLogger(SevenStarsDifficulty::class.java)

    /** 首发闪光爆炸倍率：迟暮 100% / 砺刃 125% / 破晓 200%。 */
    val FIRST_HIT_MULT = ScalingEntry(1.00f, 1.25f, 2.00f)

    /** 每跳伤害提升（加算于 (1+…) 区）：迟暮 +50% / 砺刃 +62.5% / 破晓 +100%。 */
    val PER_JUMP_BONUS = ScalingEntry(0.50f, 0.625f, 1.00f)

    /** 累计提升上限：迟暮 +100% / 砺刃 +175% / 破晓 +400%。 */
    val BONUS_CAP = ScalingEntry(1.00f, 1.75f, 4.00f)

    // ---- 常量（不缩放，设计案定案） ----

    /** 最大折跃次数（含首发），无难度系数影响。固定 7 跳定案：无击杀门槛。 */
    const val MAX_JUMPS = 7

    /** 连跳冷却（秒）：每跳落点确定后 0.3s 立即计算下一跳位置（不等上一跳爆炸结算）。 */
    const val CHAIN_COOLDOWN = 0.3f

    /**
     * 裂隙延迟爆炸的起爆征兆时长（秒，对齐原版裂隙洪流地雷 proximity delay 0.5s）：
     * 折跃落点先产生裂隙征兆（ping 光圈 + windup 音），0.5s 后裂隙爆炸结算伤害
     * （伤害量与爆炸范围不变，仅结算时点延后）。
     */
    const val EXPLOSION_DELAY = 0.5f

    /** 折跃范围 = 最终武器射程 × 本系数（吃射程修正）。 */
    const val JUMP_RANGE_MULT = 0.5f

    /** 闪光爆炸 AoE 半径（su，设计案待裁定提案值 100）。 */
    const val AOE_RADIUS = 100f

    /** 对舰终结基础段伤害（面板倍率）：单段 50%。 */
    const val TERMINAL_BASE_FRACTION = 0.5f

    /** v5 多段终结逐段递增（面板倍率/段）：+25%。 */
    const val TERMINAL_STEP_FRACTION = 0.25f

    /** v5 多段终结段伤害上限（面板倍率）：200%。 */
    const val TERMINAL_MAX_FRACTION = 2.0f

    /** v5 多段终结段间隔（秒）：0.12s 次第绽开。 */
    const val TERMINAL_SEGMENT_INTERVAL = 0.12f

    /** 破晓档判定阈值（k_s >= 5 解锁敌版多段终结）。 */
    const val DAWN_SCALE_THRESHOLD = 5f

    /** 无主弹体 WARN 的 once 守卫（罕见路径，不刷屏）。 */
    @Volatile
    private var nullSourceWarned = false

    /**
     * 发射时刻快照的难度取值（同一发弹体生命周期内恒定）。
     *
     * @property firstHitMult 首发闪光爆炸倍率（面板乘区）。
     * @property perJumpBonus 每跳伤害提升（加算于 (1+…) 区）。
     * @property bonusCap 累计提升上限。
     * @property multiSegmentTerminal 对舰终结是否为 v5 多段（破晓敌版限定；玩家恒 false）。
     */
    data class SevenStarsTuning(
        val firstHitMult: Float,
        val perJumpBonus: Float,
        val bonusCap: Float,
        val multiSegmentTerminal: Boolean,
    )

    /**
     * 按来源快照三锚点：玩家（owner == 0）固定 v2 且恒单段终结；
     * 敌方/友军 AI 走 [DifficultyTuningImpl] 的 k_s 映射，k_s >= 5 解锁多段终结；
     * 无主弹体（source == null，罕见）按敌方口径取值并 WARN 一次。
     */
    fun snapshot(source: ShipAPI?): SevenStarsTuning {
        if (source == null && !nullSourceWarned) {
            nullSourceWarned = true
            log.warn("“七星”折跃发射器弹体无来源舰船，难度取值按敌方口径（k_s 映射）结算")
        }
        val playerOwned = source?.owner == 0
        fun pick(e: ScalingEntry): Float = if (playerOwned) e.v2 else DifficultyTuningImpl.value(e)
        return SevenStarsTuning(
            firstHitMult = pick(FIRST_HIT_MULT),
            perJumpBonus = pick(PER_JUMP_BONUS),
            bonusCap = pick(BONUS_CAP),
            multiSegmentTerminal = !playerOwned && DifficultyTuningImpl.fixedScale >= DAWN_SCALE_THRESHOLD,
        )
    }
}
