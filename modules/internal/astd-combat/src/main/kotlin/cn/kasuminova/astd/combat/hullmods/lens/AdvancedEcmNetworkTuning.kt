package cn.kasuminova.astd.combat.hullmods.lens

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import com.fs.starfarer.api.combat.ShipAPI

/**
 * 先进电子对抗网络（决明级 ZW-001 内置 Hullmod，hullmod id：astd_advanced_ecm_network）
 * （purple/10-unique.md §1）的机制数值声明与纯函数。
 *
 * 接入原版电子战（ElectronicWarfareScript）：每舰 ECM 贡献读自各舰 dynamic stat
 * [ECM_FLAT_STAT]（百分数单位，原版 ECM Package 为 1/2/3/4）。本网络的两条效果：
 *
 * 1. 自身 + 存活友军分档贡献：汇总后写到本舰 [ECM_FLAT_STAT] 上（原版按舰累加即得舰队总额）；
 * 2. 敌方每舰贡献上限：原版没有"每舰贡献上限"概念（只有 per-ship 承受上限
 *    electronic_warfare_penalty_max_for_ship_mod），因此对每艘敌舰的 [ECM_FLAT_STAT]
 *    做负向 flat clamp（[clampDelta]），语义即"敌方每舰最多贡献 X%"。
 *
 * 三锚点查值集中在此声明，供 hullmod 每帧实时解析（LunaLib 设置变更即时生效），
 * 并由单元测试直接驱动。玩家来源（owner == 0）按我方档位取值（默认砺刃 v2）。
 */
object AdvancedEcmNetworkTuning {

    /** Hullmod 注册 id（csv/注册接线由装配侧完成）。 */
    const val HULLMOD_ID = "astd_advanced_ecm_network"

    /** 原版每舰 ECM 贡献的 dynamic stat key（ElectronicWarfareScript 逐舰累加该值）。 */
    const val ECM_FLAT_STAT = "electronic_warfare_flat"

    /** 自身电子战强度（%，v1 5 / v2 10 / v5 25，三锚点 LINEAR）。 */
    val SELF_ECM = ScalingEntry(5f, 10f, 25f)

    /**
     * 每个存活友军按舰级提供的电子战强度（%）：护卫舰 v1 2 / v5 4，驱逐舰 3 / 6，
     * 巡洋舰 4 / 8，主力舰 5 / 10。设计文档只定 v1/v5 两端锚点；
     * **裁定**：v2（玩家默认砺刃档）取 v1→v5 的线性中点（2.5/3.75/5/6.25），
     * 使 LINEAR 映射在全难度区间为一条直线——这是有意的锚点插值而非取整美化，
     * 后续调平衡时请直接改锚点，不要把 v2 圆整为整数。
     */
    val ALLY_ECM_FRIGATE = ScalingEntry(2f, 2.5f, 4f)
    val ALLY_ECM_DESTROYER = ScalingEntry(3f, 3.75f, 6f)
    val ALLY_ECM_CRUISER = ScalingEntry(4f, 5f, 8f)
    val ALLY_ECM_CAPITAL = ScalingEntry(5f, 6.25f, 10f)

    /** 敌方每舰贡献上限（%，固定值不随难度缩放；按敌舰舰级：护卫/驱逐/巡洋/主力 4/8/12/16）。 */
    const val ENEMY_CAP_FRIGATE = 4f
    const val ENEMY_CAP_DESTROYER = 8f
    const val ENEMY_CAP_CRUISER = 12f
    const val ENEMY_CAP_CAPITAL = 16f

    /** 一次结算所需的全部机制数值（难度解析结果；最终口径，直接可用，单位 %）。 */
    data class Values(
        /** 自身电子战强度。 */
        val selfEcm: Float,
        /** 每个存活护卫舰友军提供的电子战强度。 */
        val allyEcmFrigate: Float,
        /** 每个存活驱逐舰友军提供的电子战强度。 */
        val allyEcmDestroyer: Float,
        /** 每个存活巡洋舰友军提供的电子战强度。 */
        val allyEcmCruiser: Float,
        /** 每个存活主力舰友军提供的电子战强度。 */
        val allyEcmCapital: Float,
    )

    /** 难度取值唯一入口：玩家阵营按我方档位（默认砺刃 v2）映射，其余阵营按轨一 k_s 映射。 */
    fun resolve(tuning: DifficultyTuning, isPlayer: Boolean): Values = Values(
        selfEcm = tuning.valueFor(SELF_ECM, isPlayer),
        allyEcmFrigate = tuning.valueFor(ALLY_ECM_FRIGATE, isPlayer),
        allyEcmDestroyer = tuning.valueFor(ALLY_ECM_DESTROYER, isPlayer),
        allyEcmCruiser = tuning.valueFor(ALLY_ECM_CRUISER, isPlayer),
        allyEcmCapital = tuning.valueFor(ALLY_ECM_CAPITAL, isPlayer),
    )

    /** 写入本舰 [ECM_FLAT_STAT] 的总量（%）：自身强度 + Σ 各档存活友军贡献（计数负值按 0 处理）。 */
    fun totalOwnEcm(values: Values, frigates: Int, destroyers: Int, cruisers: Int, capitals: Int): Float =
        values.selfEcm +
            frigates.coerceAtLeast(0) * values.allyEcmFrigate +
            destroyers.coerceAtLeast(0) * values.allyEcmDestroyer +
            cruisers.coerceAtLeast(0) * values.allyEcmCruiser +
            capitals.coerceAtLeast(0) * values.allyEcmCapital

    /** 敌方每舰贡献上限按舰级查值（非标准档位按护卫舰处理，与 affix.AffixShared.bySize 口径一致）。 */
    fun enemyCapFor(hullSize: ShipAPI.HullSize?): Float = when (hullSize) {
        ShipAPI.HullSize.CAPITAL_SHIP -> ENEMY_CAP_CAPITAL
        ShipAPI.HullSize.CRUISER -> ENEMY_CAP_CRUISER
        ShipAPI.HullSize.DESTROYER -> ENEMY_CAP_DESTROYER
        else -> ENEMY_CAP_FRIGATE
    }

    /** 对敌舰当前贡献值的 clamp 修正量（负向 flat；未超上限返回 0，调用侧不写入）。 */
    fun clampDelta(current: Float, cap: Float): Float = if (current > cap) cap - current else 0f
}
