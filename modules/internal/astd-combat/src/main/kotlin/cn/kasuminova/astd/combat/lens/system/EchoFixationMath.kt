package cn.kasuminova.astd.combat.lens.system

/**
 * 回声定影（Echo Fixation）纯数学（spec §2）。
 * 认知撕裂增伤、站桩重合衰减、范围随系统射程缩放——无副作用纯函数，便于单测与平衡。
 */
object EchoFixationMath {
    private const val TEAR_BONUS_AT_M1 = 0.50f   // m=1: +50%
    private const val TEAR_BONUS_AT_M2 = 1.00f   // m=2: +100%

    private const val STANDSTILL_MAX = 2.00f     // 重合: +200%
    private const val STANDSTILL_MIN = 0.25f     // 边缘: +25%

    /** 认知撕裂基础增伤（难度系数 m∈[1,2] 线性插值）。 */
    fun cognitiveTearBonus(difficultyFactor: Float): Float {
        val m = difficultyFactor.coerceIn(1f, 2f)
        return TEAR_BONUS_AT_M1 + (TEAR_BONUS_AT_M2 - TEAR_BONUS_AT_M1) * (m - 1f)
    }

    /** 站桩重合度增伤：dist=0 满效 STANDSTILL_MAX，dist>=有效范围 衰减到 STANDSTILL_MIN，线性。 */
    fun standstillBonus(distFromPast: Float, baseRange: Float, systemRangeMult: Float): Float {
        val range = (baseRange * systemRangeMult).coerceAtLeast(1f)
        val t = (distFromPast / range).coerceIn(0f, 1f)
        return STANDSTILL_MAX + (STANDSTILL_MIN - STANDSTILL_MAX) * t
    }

    /** 定影场半径随系统射程缩放。 */
    fun fieldRadius(baseRadius: Float, systemRangeMult: Float): Float =
        baseRadius * systemRangeMult.coerceAtLeast(0.01f)
}
