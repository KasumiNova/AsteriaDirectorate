package cn.kasuminova.astd.combat.hullmods.lens

import cn.kasuminova.astd.api.difficulty.DifficultyTuning
import cn.kasuminova.astd.api.difficulty.ScalingEntry
import com.fs.starfarer.api.combat.ShipAPI

/**
 * 先进电子对抗网络（决明级 ZW-001 内置 Hullmod，hullmod id：astd_advanced_ecm_network）
 * （purple/10-unique.md §1）的机制数值声明与纯函数。
 *
 * 接入原版电子战（ElectronicWarfareScript）结算链路，两大模块：
 *
 * 1. **演算核心**：每舰 ECM 贡献读自各舰 dynamic stat [ECM_FLAT_STAT]（百分数单位，
 *    原版 ECM Package 为 1/2/3/4）。本网络自身 + 存活友军分档贡献汇总后写到本舰
 *    [ECM_FLAT_STAT]（原版按舰累加即得舰队总额）；敌方每舰贡献做负向 flat clamp
 *    （[clampDelta]），语义即"敌方每舰最多贡献 X%"（原版没有每舰贡献上限概念）。
 * 2. **战术反制**（合并原引力电子干扰力场 astd_grav_ew_field 的全部机制）：
 *    - 我方电子战最大效果提升：对本侧全部指挥官的 [EW_MAX_STAT] 写 flat 增量
 *      （原版每侧最大效果 = [VANILLA_MAX_EFFECT]（写死 BASE_MAXIMUM=10）+ 各指挥官该值最大值），
 *      每个被本侧占领的战术点再额外加计（[commanderBoost]）。指挥官 PersonAPI 的
 *      dynamic stat 跨战斗存活，必须逐帧对账并在 hulk/离场/战斗结束路径全量收口。
 *    - 我方战术点位占领速度提升：对全部存活友军写 dynamic stat [CAP_RATE_STAT] 乘区。
 *    - 附加四项减益：复算原版公式（[penaltyDealt]，含双方 ECM 对抗折扣与原版取整口径）
 *      得出本侧实际造成的 EW 效果，幅度 = 实际效果百分数（[debuffMagnitude]，与原版的
 *      射程减益口径一致），对全部存活敌舰施加最终乘区：empDamageTakenMult /
 *      shieldDamageTakenMult 提升（EMP 抗性与护盾效率降低）、systemCooldownBonus 提升
 *      （冷却时间增加）、systemRegenBonus 削减（充能时间增加）。
 *
 * 友军分档锚点裁定：设计文档给出四个预设难度档（迟暮/砺刃/远征/破晓）的完整四值，
 * 但 ScalingEntry 只有三锚点——锚点取 迟暮/砺刃/破晓 三档，远征档由 LINEAR 插值
 * （如护卫舰 k3 ≈ 5.33，与设计值 6 略有偏差，属有意的锚点取舍，调平衡请直接改锚点）。
 *
 * 三锚点查值集中在此声明，供 hullmod 每帧实时解析（LunaLib 设置变更即时生效），
 * 并由单元测试直接驱动。玩家来源（owner == 0）按我方档位取值（默认砺刃 v2）。
 */
object AdvancedEcmNetworkTuning {

    /** Hullmod 注册 id（csv/注册接线由装配侧完成）。 */
    const val HULLMOD_ID = "astd_advanced_ecm_network"

    /** 原版每舰 ECM 贡献的 dynamic stat key（ElectronicWarfareScript 逐舰累加该值）。 */
    const val ECM_FLAT_STAT = "electronic_warfare_flat"

    /** 原版阵营最大效果的指挥官 dynamic stat key（最大效果 = 10 + 各指挥官该值的最大值）。 */
    const val EW_MAX_STAT = "electronic_warfare_max"

    /** 原版战术点占领速率的逐舰 dynamic stat key（BattleObjective 占领结算读取）。 */
    const val CAP_RATE_STAT = "ship_objective_cap_rate_mult"

    /** 原版电子战最大效果基准（%，ElectronicWarfareScript.BASE_MAXIMUM 写死值，仅作换算基准）。 */
    const val VANILLA_MAX_EFFECT = 10f

    /** 原版每座传感阵列目标提供的 ECM（%，ElectronicWarfareScript.PER_JAMMER，结算复算用）。 */
    const val SENSOR_ARRAY_ECM = 5f

    /** 原版传感阵列目标类型 id（结算复算用）。 */
    const val SENSOR_ARRAY_TYPE = "sensor_array"

    /** 全部 stat 修饰句柄的统一前缀（按源舰 id 区分多网络源；跨战斗残留安全网按此前缀识别）。 */
    const val MOD_ID_PREFIX = "astd_advanced_ecm_network:"

    /** 已删除的引力电子干扰力场（astd_grav_ew_field）旧前缀：存量存档残留修饰的安全网识别用。 */
    const val LEGACY_MOD_ID_PREFIX = "astd_grav_ew_field:"

    /** 自身电子战强度（%，v1 15 / v2 20 / v5 35，三锚点 LINEAR）。 */
    val SELF_ECM = ScalingEntry(15f, 20f, 35f)

    /** 每个存活护卫舰友军提供的电子战强度（%，v1 2 / v2 4 / v5 8；远征档插值，见类注释裁定）。 */
    val ALLY_ECM_FRIGATE = ScalingEntry(2f, 4f, 8f)

    /** 每个存活驱逐舰友军提供的电子战强度（%，v1 4 / v2 8 / v5 16；远征档插值）。 */
    val ALLY_ECM_DESTROYER = ScalingEntry(4f, 8f, 16f)

    /** 每个存活巡洋舰友军提供的电子战强度（%，v1 6 / v2 12 / v5 24；远征档插值）。 */
    val ALLY_ECM_CRUISER = ScalingEntry(6f, 12f, 24f)

    /** 每个存活主力舰友军提供的电子战强度（%，v1 8 / v2 16 / v5 32；远征档插值）。 */
    val ALLY_ECM_CAPITAL = ScalingEntry(8f, 16f, 32f)

    /** 敌方每舰贡献上限（%，固定值不随难度缩放；按敌舰舰级：护卫/驱逐/巡洋/主力 4/8/12/16）。 */
    const val ENEMY_CAP_FRIGATE = 4f
    const val ENEMY_CAP_DESTROYER = 8f
    const val ENEMY_CAP_CRUISER = 12f
    const val ENEMY_CAP_CAPITAL = 16f

    /** 我方电子战最大效果强度提升倍率（v1 +50% / v2 +100% / v5 +250%，三锚点 LINEAR）。 */
    val MAX_EFFECT_BOOST = ScalingEntry(0.5f, 1.0f, 2.5f)

    /** 每个被本侧占领的战术点额外提供的最大效果强度提升倍率（v1 20% / v2 30% / v5 60%）。 */
    val POINT_MAX_BOOST = ScalingEntry(0.2f, 0.3f, 0.6f)

    /** 我方战术点位占领速度提升倍率（v1 +100% / v2 +200% / v5 +500%）。 */
    val CAPTURE_RATE_BOOST = ScalingEntry(1.0f, 2.0f, 5.0f)

    /** 一次结算所需的全部机制数值（难度解析结果；最终口径，直接可用，ECM 类单位 %）。 */
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
        /** 我方电子战最大效果强度提升倍率。 */
        val maxEffectBoost: Float,
        /** 每个被占领战术点的最大效果强度提升倍率。 */
        val pointMaxBoost: Float,
        /** 我方战术点位占领速度乘区（1 + 提升倍率）。 */
        val captureRateMult: Float,
    )

    /** 难度取值唯一入口：玩家阵营按我方档位（默认砺刃 v2）映射，其余阵营按轨一 k_s 映射。 */
    fun resolve(tuning: DifficultyTuning, isPlayer: Boolean): Values = Values(
        selfEcm = tuning.valueFor(SELF_ECM, isPlayer),
        allyEcmFrigate = tuning.valueFor(ALLY_ECM_FRIGATE, isPlayer),
        allyEcmDestroyer = tuning.valueFor(ALLY_ECM_DESTROYER, isPlayer),
        allyEcmCruiser = tuning.valueFor(ALLY_ECM_CRUISER, isPlayer),
        allyEcmCapital = tuning.valueFor(ALLY_ECM_CAPITAL, isPlayer),
        maxEffectBoost = tuning.valueFor(MAX_EFFECT_BOOST, isPlayer),
        pointMaxBoost = tuning.valueFor(POINT_MAX_BOOST, isPlayer),
        captureRateMult = 1f + tuning.valueFor(CAPTURE_RATE_BOOST, isPlayer),
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

    /**
     * 写入本侧指挥官 [EW_MAX_STAT] 的 flat 增量（%）：
     * 基础提升（[Values.maxEffectBoost] × [VANILLA_MAX_EFFECT]）+ 每个被本侧占领战术点
     * 额外加计（[Values.pointMaxBoost] × [VANILLA_MAX_EFFECT]，计数负值按 0 处理）。
     */
    fun commanderBoost(values: Values, capturedPoints: Int): Float =
        VANILLA_MAX_EFFECT * (values.maxEffectBoost + capturedPoints.coerceAtLeast(0) * values.pointMaxBoost)

    /**
     * 复算原版电子战对本侧敌对方实际造成的射程削减（%，与 ElectronicWarfareScript 同公式）：
     * 原版 getTotalAndMaximum 以 int[] 返回（总额/上限先 int 截断），效果 =
     * min(本侧总额, 本侧上限)，双方均有 ECM 时按 本侧/(双方和) 折扣，最后 Math.round 半进位。
     */
    fun penaltyDealt(ownTotal: Float, ownMax: Float, enemyTotal: Float): Int {
        val total = ownTotal.toInt()
        val max = ownMax.toInt()
        val enemy = enemyTotal.toInt()
        var penalty = minOf(total, max).toFloat()
        if (enemy > 0 && penalty > 0f) penalty *= total.toFloat() / (enemy + total)
        return Math.round(penalty)
    }

    /** 附加四项减益幅度：实际 EW 效果百分数直接作比例（10% 效果 → 0.10，与原版射程减益口径一致）。 */
    fun debuffMagnitude(penalty: Int): Float = penalty / 100f

    /** 从 stat 修饰来源集合中筛出本网络的句柄（含已删除旧力场前缀，跨战斗残留安全网识别用）。 */
    fun fieldModSources(sources: Collection<String>): List<String> =
        sources.filter { it.startsWith(MOD_ID_PREFIX) || it.startsWith(LEGACY_MOD_ID_PREFIX) }
}
