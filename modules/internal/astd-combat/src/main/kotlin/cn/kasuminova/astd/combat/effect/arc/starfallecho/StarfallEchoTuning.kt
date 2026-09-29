package cn.kasuminova.astd.combat.effect.arc.starfallecho

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingTable

/**
 * 坠星残响（XC-001 内置主炮，规格 10-signature）的机制数值声明与纯函数。
 *
 * 动机：连射序数（5 发一轮，第 5 发替换为 200% 弹体）、结构谐振叠层易伤、
 * 按层数规模化的爆炸结算与弹匣禁射闸的数值集中在一处声明；爆炸半径/伤害映射、
 * 禁射判定与连射序数推进均为纯函数，供 OnFire/OnHit/EveryFrame 调用并由单元测试直接驱动。
 *
 * 数值缩放口径：每层谐振易伤与爆炸伤害倍率走五档精确查表（[ScalingTable]）；
 * 玩家来源（owner == 0）按我方档位取值（默认砺刃 v2，见 DifficultyTuning.valueFor）。
 * 设计案只给基准口径（每层 10% 易伤、爆炸 150su 起每层 +150su、每层消耗 +50% 第 5 发伤害），
 * 各档数值为裁定值（k2 = 基准）。
 */
object StarfallEchoTuning {

    /** 连射一轮发数（第 [BURST_SIZE] 发为强化弹）。 */
    const val BURST_SIZE = 5

    /** 结构谐振层数上限（前 4 发每发命中叠 1 层）。 */
    const val RESONANCE_MAX_STACKS = 4

    /** 爆炸基础半径（su）：第 5 发命中恒爆炸，0 层 = 150su 纯视觉，每层 +150su，4 层封顶 750su。 */
    const val EXPLOSION_BASE_RADIUS = 150f

    /** 每层被消耗的谐振对第 5 发直击伤害的提升（+50%/层，只作用于直击，不计入爆炸结算）。 */
    const val FINAL_STACK_DAMAGE_BONUS = 0.5f

    /** 第 5 发面板伤害倍率（200% 伤害）。 */
    const val FINAL_DAMAGE_MULT = 2f

    /** 第 5 发碰撞半径倍率（200% 尺寸）。 */
    const val FINAL_SIZE_MULT = 2f

    /** 第 5 发额外辐能产出：单发 1125 × 300% − 引擎已结算的 1125 = 2250。 */
    const val FINAL_FLUX_EXTRA = 2250f

    /** 弹匣禁射阈值（隐藏机制）：弹药低于本值且不在连射中时禁止起射新一轮。 */
    const val AMMO_GATE = 5

    /** 连射序数重置间隔（秒）：连射间隔 0.2s 的 2 倍，距上一发超过本值视为新一轮（从 1 起）。 */
    const val BURST_RESET_SECONDS = 0.4f

    /** 每层结构谐振易伤（五档查表：k1 5% / k2 10% / k3 12.5% / k4 15% / k5 20%；k2 = 设计案基准）。 */
    val RESONANCE_VULN_PER_STACK = ScalingTable(0.05f, 0.10f, 0.125f, 0.15f, 0.20f)

    /** 爆炸伤害倍率（五档查表：k1 75% / k2 100% / k3 125% / k4 150% / k5 200%；裁定值）。 */
    val EXPLOSION_DAMAGE_MULT = ScalingTable(0.75f, 1.00f, 1.25f, 1.50f, 2.00f)

    /** 一次命中路由所需的全部机制数值（难度解析结果）。 */
    data class Values(
        /** 每层谐振易伤（乘区增量，如 0.10 = +10% 承伤）。 */
        val vulnPerStack: Float,
        /** 爆炸伤害倍率（作用于「第 5 发面板 × 层数」）。 */
        val explosionDamageMult: Float,
        /** 来源是否为玩家（owner == 0）。 */
        val isPlayer: Boolean,
    )

    /**
     * 难度取值唯一入口：玩家阵营按我方档位（默认砺刃 v2）五档查表，其余阵营按轨一 k_s 查表。
     * 每次命中调用一次（不缓存），保证 LunaLib 设置变更即时生效。
     */
    fun resolve(tuning: DifficultyTuning, isPlayer: Boolean): Values = Values(
        vulnPerStack = tuning.valueFor(RESONANCE_VULN_PER_STACK, isPlayer),
        explosionDamageMult = tuning.valueFor(EXPLOSION_DAMAGE_MULT, isPlayer),
        isPlayer = isPlayer,
    )

    /**
     * 爆炸半径（纯函数）：基础半径 ×（层数+1），0 层 = 150su 纯视觉爆炸，4 层封顶 750su。
     * 裁定口径：0 层也爆炸（仅视觉），伤害按层数结算（层数 0 即 0，见 [explosionDamage]）。
     */
    fun explosionRadius(stacks: Int): Float = EXPLOSION_BASE_RADIUS * (stacks.coerceAtLeast(0) + 1)

    /** 第 5 发总伤害（纯函数）：面板 ×（1 + [FINAL_STACK_DAMAGE_BONUS]×层数）；0 层 = 面板。 */
    fun finalShotDamage(finalDamage: Float, stacks: Int): Float =
        finalDamage * (1f + FINAL_STACK_DAMAGE_BONUS * stacks.coerceAtLeast(0))

    /**
     * 第 5 发直击补伤（纯函数）：面板 × [FINAL_STACK_DAMAGE_BONUS]×层数。
     * 直击面板已由引擎原生结算，本函数只给「提升部分」，由脚本 applyDamage 补给直击目标
     * （shieldCovers/resolveShipDamagePoint 同款判例口径；直击目标仍豁免 AOE）。
     */
    fun finalShotBonusDamage(finalDamage: Float, stacks: Int): Float =
        finalDamage * FINAL_STACK_DAMAGE_BONUS * stacks.coerceAtLeast(0)

    /**
     * 爆炸结算伤害（纯函数）：第 5 发面板 × 层数 × 难度倍率（设计案「等额规模」口径——
     * 爆炸规模为 100%~400%，AOE 伤害与规模等额，即面板的 100%~400%）；0 层恒 0（无 AOE 伤害）。
     * 每层 +50% 的谐振提升只作用于第 5 发直击（提升部分由 [finalShotBonusDamage] 单独补给
     * 直击目标），不计入 AOE 基数：计入会把 4 层 AOE 抬到面板的 (1+0.5×4)×4 = 12 倍，
     * 远超设计案 400% 的规模上限（实机判例：AOE 波及目标伤害上万，被直击舰船仅几千）。
     */
    fun explosionDamage(finalDamage: Float, stacks: Int, mult: Float): Float =
        finalDamage * stacks.coerceAtLeast(0) * mult

    /**
     * 弹匣禁射闸（纯函数）：弹药低于 [AMMO_GATE] 且不在连射中时不允许起射新一轮；
     * 连射进行中放行（否则会切断已起射的 5 发 burst）。
     */
    fun canFire(ammo: Int, inBurst: Boolean): Boolean = inBurst || ammo >= AMMO_GATE

    /**
     * 连射序数推进（纯函数）：距上一发超过 [BURST_RESET_SECONDS] 视为新一轮（序数归 1），
     * 否则递增。首发射击传入 prevOrdinal = 0 / lastFireTime 远早于 now 时自然归 1。
     */
    fun nextBurstOrdinal(prevOrdinal: Int, lastFireTime: Float, now: Float): Int =
        if (now - lastFireTime > BURST_RESET_SECONDS) 1 else prevOrdinal + 1
}
