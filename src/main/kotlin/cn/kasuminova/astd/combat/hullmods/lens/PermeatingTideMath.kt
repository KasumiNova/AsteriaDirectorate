package cn.kasuminova.astd.combat.hullmods.lens

/**
 * 渗透潮汐（Permeating Tide，spec §5 / `purple/10-unique.md` §1 插件③）的纯数学换算与判定。
 *
 * 动机：将「涨潮叠深水标记的间隔 = f(距离, 难度)」「场内判定」「过载退潮判定」三段可单测的纯逻辑，
 * 从 [ASTDLensPermeatingTideHullMod] 的运行时遍历中剥离，使数值边界可被
 * [PermeatingTideMath] 单测覆盖（advanceInCombat 集成层不单测）。
 *
 * 数值锚点（spec §5.2 + `purple/10-unique.md` §1 插件③，v0 待平衡）：
 * - 潮汐场半径 ~2500su；场内敌舰每 2.5s~5s 叠 1 层深水标记，越近越快。
 * - 「最快叠加范围 ≤1000su」（最快 2.5s/层），「最慢叠加范围 ≤2000su」（最慢 5s/层）。
 *   故插值斜坡落在 [1000, 2000]su；(2000, 2500]su 区间仍在场内、保持最慢 5s 间隔；出 2500su 不叠。
 * - 难度系数 m∈[1,2]（仅敌对单位经 AffixUtil.getK 得 m=1+k）：间隔 = base / m，
 *   故 m=2 时最快 2.5/2 = 1.25s/层（spec「相当于最快约 1.25s/层」）。
 * - 退潮：本舰过载（[shouldEbb]）即清空全场标记（调用方 LensMarks.clearAllLensMarks）。
 */
object PermeatingTideMath {

    /** 潮汐场半径（su，spec §5.2「潮汐场半径 ~2500su」）。出此距离不叠深水标记。 */
    const val FIELD_RADIUS: Float = 2500f

    /** 最快叠加范围上界（su，spec §5.2「最快叠加范围 ≤1000su」）。≤此距离取最快间隔。 */
    const val NEAR_DISTANCE: Float = 1000f

    /**
     * 最慢叠加斜坡终点（su，spec §5.2「最慢叠加范围 ≤2000su」）。
     * [NEAR_DISTANCE, FAR_RAMP_DISTANCE] 间线性插值；(FAR_RAMP_DISTANCE, FIELD_RADIUS] 保持最慢间隔。
     */
    const val FAR_RAMP_DISTANCE: Float = 2000f

    /** 最快叠加间隔基线（s，m=1，spec §5.2「叠加速率 2.5s~5s/层」的快端）。 */
    const val BASE_INTERVAL_NEAR: Float = 2.5f

    /** 最慢叠加间隔基线（s，m=1，叠加速率慢端）。 */
    const val BASE_INTERVAL_FAR: Float = 5f

    /** 难度系数下/上界（m∈[1,2]，spec §5.1「敌方难度缩放」）。 */
    private const val DIFFICULTY_MIN = 1f
    private const val DIFFICULTY_MAX = 2f

    /**
     * 某敌舰按其与本舰距离 [dist] 应得的「叠 1 层深水标记」间隔（秒）。
     *
     * - dist ≤ [NEAR_DISTANCE]：最快间隔 [BASE_INTERVAL_NEAR]。
     * - [NEAR_DISTANCE] < dist ≤ [FAR_RAMP_DISTANCE]：在 [BASE_INTERVAL_NEAR, BASE_INTERVAL_FAR] 线性插值（越近越快）。
     * - [FAR_RAMP_DISTANCE] < dist ≤ [FIELD_RADIUS]：保持最慢间隔 [BASE_INTERVAL_FAR]（仍在场内、仍叠）。
     * - dist > [FIELD_RADIUS]：返回 [Float.POSITIVE_INFINITY] 表示「不叠」（出场）。
     *
     * 难度：基线间隔 / m（m=[difficultyFactor] 夹紧到 [1,2]）——m 越大叠加越快。
     * 「不叠」对任何 m 仍为 infinity（infinity / m = infinity），故难度不会让出场距离开始叠。
     *
     * @param nearDist 最快叠加范围上界（默认 [NEAR_DISTANCE]）。
     * @param farDist 插值斜坡终点（默认 [FAR_RAMP_DISTANCE]）。
     * @param fieldRadius 场半径（默认 [FIELD_RADIUS]）；出此距离不叠。
     * @param baseNear 最快间隔基线（默认 [BASE_INTERVAL_NEAR]）。
     * @param baseFar 最慢间隔基线（默认 [BASE_INTERVAL_FAR]）。
     * @param difficultyFactor 难度系数 m（夹紧到 [1,2]）。
     */
    fun markIntervalForDistance(
        dist: Float,
        nearDist: Float = NEAR_DISTANCE,
        farDist: Float = FAR_RAMP_DISTANCE,
        fieldRadius: Float = FIELD_RADIUS,
        baseNear: Float = BASE_INTERVAL_NEAR,
        baseFar: Float = BASE_INTERVAL_FAR,
        difficultyFactor: Float = 1f,
    ): Float {
        if (dist > fieldRadius) return Float.POSITIVE_INFINITY

        val baseInterval = when {
            dist <= nearDist -> baseNear
            dist >= farDist -> baseFar
            else -> {
                val t = (dist - nearDist) / (farDist - nearDist)
                baseNear + (baseFar - baseNear) * t
            }
        }

        val m = difficultyFactor.coerceIn(DIFFICULTY_MIN, DIFFICULTY_MAX)
        return baseInterval / m
    }

    /**
     * 场内判定：敌舰与本舰距离 [dist] 是否落在潮汐场半径 [fieldRadius] 内（含边界）。
     *
     * 含边界（`<=`）：2500su 是场半径上限，恰在边界的敌舰仍受潮汐压制。
     */
    fun isInTideField(dist: Float, fieldRadius: Float = FIELD_RADIUS): Boolean =
        dist <= fieldRadius

    /**
     * 退潮判定：本舰是否处于过载（[isOverloaded]）——过载即潮汐退去，需清空全场标记。
     *
     * 提取为纯函数仅为对齐 spec「退潮反制」语义并便于断言，逻辑等同于 isOverloaded 本身。
     */
    fun shouldEbb(isOverloaded: Boolean): Boolean = isOverloaded
}
