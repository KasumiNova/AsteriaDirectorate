package cn.kasuminova.astd.campaign.bounty

import cn.kasuminova.astd.api.difficulty.StrengthSnapshot
import cn.kasuminova.astd.api.AstdLog
import kotlin.math.min

/**
 * 难度/缩放模型（轨二：进程动态系数）：
 * - 难度系数（赏金 tier 规模）最大 300%（x3），来自赏金定义，不参与进程评估
 * - 玩家进程系数 p：由 [FleetStrengthAssessment] 评估（sqrt 压缩，封顶 [0.85, 2.2]）
 * - 总上限 1500%（x15）
 *
 * 词缀强度输入 k_p 取自评估快照的归一化 k（p 在 [0.85, 2.2] 区间线性归一化），
 * 与舰队规模倍率同源，保证"规模与词缀强度一致反映进程"。
 */
object DifficultyModel {

    data class Scale(
        val difficultyMult: Float,
        val playerMult: Float,
        val totalMult: Float,
        /**
         * 进程归一化 k_p：0..1，仅用于词缀强度选量（生成表专用，机制数值禁止从此取）。
         */
        val k: Float,
    )

    /**
     * 将 Threat Tier 映射为“舰队规模难度系数”（<= 3.0）。
     *
     * 说明：这里刻意给 T5 一个硬封顶 3.0，避免叠加词缀后变成纯数值怪。
     */
    fun difficultyMultFromTier(threatTier: Int): Float = when (threatTier.coerceIn(1, 5)) {
        1 -> 1.00f
        2 -> 1.25f
        3 -> 1.65f
        4 -> 2.25f
        else -> 3.00f
    }

    /**
     * 评估玩家进程战力：以 tier 缩放后的基准 FP 为参照，输出 sqrt 压缩后的 p 与归一化 k_p。
     */
    fun assessPlayerStrength(baselineFP: Int): StrengthSnapshot {
        return FleetStrengthAssessmentImpl.assess(baselineFP.toFloat())
    }

    fun compute(threatTier: Int, baselineFP: Int): Scale {
        val difficulty = difficultyMultFromTier(threatTier)
        val reference = (baselineFP * difficulty).toInt()
        val snapshot = assessPlayerStrength(reference)
        val player = snapshot.p
        val total = min(15f, difficulty * player)

        AstdLog.logger.info(
            "[ASTD] 赏金进程评估：tier=$threatTier, ref=$reference, " +
                snapshot.breakdown.joinToString(", ") { "${it.label}=${"%.2f".format(it.value)}" } +
                " => p=${"%.3f".format(player)}, k_p=${"%.3f".format(snapshot.k)}, totalMult=${"%.2f".format(total)}",
        )

        return Scale(difficulty, player, total, snapshot.k)
    }
}
