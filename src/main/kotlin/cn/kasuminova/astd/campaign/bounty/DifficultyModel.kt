package cn.kasuminova.astd.campaign.bounty

import com.fs.starfarer.api.Global
import kotlin.math.max
import kotlin.math.min

/**
 * 难度/缩放模型：
 * - 难度系数（舰队规模）最大 300%（x3）
 * - 玩家舰队“超模系数”最大 500%（x5）
 * - 总上限 1500%（x15）
 */
object DifficultyModel {

    data class Scale(
        val difficultyMult: Float,
        val playerMult: Float,
        val totalMult: Float,
        /**
         * 归一化难度 k：0..1，用于词缀强度与小幅数值增益。
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
     * 玩家舰队系数（<= 5.0）。
     *
     * 先采用可解释、可调的“FP 比值”启发式：
     * - playerFP / baselineFP
     * 后续可把“军官等级、S-mod、舰船质量”纳入评估。
     */
    fun playerMultFromFleetAndBaseline(baselineFP: Int): Float {
        val sector = Global.getSector() ?: return 1f
        val playerFleet = sector.playerFleet ?: return 1f
        val playerFP = max(1, playerFleet.fleetPoints)
        val base = max(1, baselineFP)
        val raw = playerFP.toFloat() / base.toFloat()
        return raw.coerceIn(1f, 5f)
    }

    fun compute(threatTier: Int, baselineFP: Int): Scale {
        val difficulty = difficultyMultFromTier(threatTier)
        val player = playerMultFromFleetAndBaseline((baselineFP * difficulty).toInt())
        val total = min(15f, difficulty * player)
        // k 用 total 的线性归一化：1 -> 0, 15 -> 1
        val k = ((total - 1f) / 14f).coerceIn(0f, 1f)
        return Scale(difficulty, player, total, k)
    }
}
