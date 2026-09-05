package cn.kasuminova.astd.campaign.bounty

import cn.kasuminova.astd.api.AstdLog
import cn.kasuminova.astd.api.difficulty.StrengthSnapshot
import cn.kasuminova.astd.impl.difficulty.DifficultyTuningImpl
import com.fs.starfarer.api.Global

/**
 * 赏金 FP = 预设 FP × 固有难度 × 玩家超模系数 × 舰队大小系数。
 *
 * 舰队大小系数取 maxShipsInFleet/maxShipsInAIFleet 中较大的配置值相对于原版 30 艘的比值，
 * 不重复相乘，也不因配置低于原版而削弱赏金。FP 预算由 [FleetComposer] 实际填满，
 * 不能在 30 艘处截断后把未生成的预算当成战力。
 * 词缀数量按固有难度归一化（v3 定案），玩家评估只改变 FP，不改变词缀档位。
 */
object DifficultyModel {

    /** 玩家超模系数上限（02 文档：最高 2×）。 */
    const val MAX_PLAYER_MULT: Float = 2.0f
    private const val VANILLA_FLEET_SIZE: Float = 30f

    data class Scale(
        /** 全局难度系数 k_s（1..5）。 */
        val difficultyMult: Float,
        /** 玩家超模系数 p（封顶 [MAX_PLAYER_MULT]）。 */
        val playerMult: Float,
        val totalMult: Float,
        /** 固有难度归一化值：0..1，用于 S/M/R 数量取档。 */
        val k: Float,
        /** 原版舰队大小配置的比例，不受 10×（难度×超模）上限截断。 */
        val fleetSizeMult: Float,
    )

    /** 以难度缩放后的预设 FP 为参照评估玩家战力。 */
    fun assessPlayerStrength(referenceFP: Int): StrengthSnapshot =
        FleetStrengthAssessmentImpl.assess(referenceFP.toFloat())

    /** 纯缩放计算；配置非法时直接暴露错误，不静默吞掉舰队大小配置。 */
    fun calculate(difficulty: Float, playerStrength: Float, maxPlayerShips: Int, maxAiShips: Int): Scale {
        require(difficulty in 1f..5f && playerStrength.isFinite() && playerStrength > 0f)
        require(maxPlayerShips > 0 && maxAiShips > 0)
        val player = playerStrength.coerceAtMost(MAX_PLAYER_MULT)
        val fleetSize = (maxOf(maxPlayerShips, maxAiShips) / VANILLA_FLEET_SIZE).coerceAtLeast(1f)
        return Scale(difficulty, player, difficulty * player * fleetSize, (difficulty - 1f) / 4f, fleetSize)
    }

    fun compute(baselineFP: Int): Scale {
        val difficulty = DifficultyTuningImpl.fixedScale
        val reference = (baselineFP * difficulty).toInt()
        val snapshot = assessPlayerStrength(reference)
        val settings = Global.getSettings()
        val scale = calculate(difficulty, snapshot.p, settings.getInt("maxShipsInFleet"), settings.getInt("maxShipsInAIFleet"))
        AstdLog.logger.info(
            "[ASTD] 赏金进程评估：k_s=$difficulty, ref=$reference, " +
                snapshot.breakdown.joinToString(", ") { "${it.label}=${"%.2f".format(it.value)}" } +
                " => p=${scale.playerMult}, fleetSize=${scale.fleetSizeMult}, affixK=${scale.k}, totalMult=${scale.totalMult}",
        )
        return scale
    }
}
