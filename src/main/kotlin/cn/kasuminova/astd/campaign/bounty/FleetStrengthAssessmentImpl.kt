package cn.kasuminova.astd.campaign.bounty

import cn.kasuminova.astd.api.difficulty.FleetStrengthAssessment
import cn.kasuminova.astd.api.difficulty.StrengthSnapshot
import com.fs.starfarer.api.Global
import kotlin.math.sqrt

/**
 * [FleetStrengthAssessment] 的实现：按设计案 §3.1 公式评估玩家当前战力。
 *
 * 评估公式：S = Σ舰船(部署点 × 品级系数 q) + Σ军官(等级 × 3) + 玩家战斗技能数 × 4。
 * 品级系数 q：s-mod +0.08 / d-mod -0.06 / 相位或自动化 +0.10 / 民用 ×0.5，封顶 [0.5, 1.5]。
 * 只统计主战成员：玩家本人不算军官，无人舰长与默认舰长不计入军官项。
 *
 * 判定口径：d-mod 按船插 `dmod` 标签计数；自动化按内置 `automated` 船插判定。
 *
 * 输出压缩：p = sqrt(S / S_ref) 封顶 [0.85, 2.2]（边际递减，防堆料惩罚）；
 * 连续评估按 EMA（α=0.3）平滑 S，防买卖船造成的规模抖动。
 *
 * 数据收集（Global sector 读取）与纯计算分离：[FleetStrengthMath] 承担全部纯计算，
 * 单元测试直接驱动纯函数，不依赖游戏环境。
 */
object FleetStrengthAssessmentImpl : FleetStrengthAssessment {

    /** EMA 平滑状态（会话级）：上一次平滑后的战力分。 */
    @Volatile
    private var smoothedScore: Float? = null

    override fun assess(referenceFP: Float): StrengthSnapshot {
        val inputs = gatherInputs()
        val smoothed = FleetStrengthMath.smooth(inputs.rawScore, smoothedScore)
        smoothedScore = smoothed
        return FleetStrengthMath.buildSnapshot(inputs, smoothed, referenceFP)
    }

    /** 从 Global sector 收集评估输入。无玩家舰队（如标题界面）时返回全零输入。 */
    private fun gatherInputs(): FleetStrengthMath.Inputs {
        val sector = Global.getSector() ?: return FleetStrengthMath.Inputs(emptyList(), emptyList(), 0)
        val fleet = sector.playerFleet ?: return FleetStrengthMath.Inputs(emptyList(), emptyList(), 0)

        val settings = Global.getSettings()
        val ships = fleet.membersWithFightersCopy
            .filter { !it.isFighterWing }
            .map { member ->
                val hullSpec = member.hullSpec
                FleetStrengthMath.ShipInput(
                    deploymentPoints = member.deploymentPointsCost,
                    sModCount = member.variant.sMods.size,
                    dModCount = member.variant.hullMods.count { settings.getHullModSpec(it).hasTag("dmod") },
                    phaseOrAutomated = hullSpec.isPhase || member.variant.hasHullMod("automated"),
                    civilian = member.isCivilian,
                )
            }

        val officerLevels = fleet.membersWithFightersCopy
            .filter { !it.isFighterWing }
            .mapNotNull { member ->
                val captain = member.captain ?: return@mapNotNull null
                if (captain.isDefault || captain.isPlayer) return@mapNotNull null
                captain.stats.level.takeIf { it > 0 }
            }

        val combatSkillCount = sector.playerPerson.stats.skillsCopy
            .filter { it.level > 0f }
            .count { Global.getSettings().getSkillSpec(it.skill.id).governingAptitudeId == "combat" }

        return FleetStrengthMath.Inputs(ships, officerLevels, combatSkillCount)
    }

    /** 测试注入平滑状态（避免用例间 EMA 串扰）。 */
    fun installSmoothedScoreForTests(score: Float?) {
        smoothedScore = score
    }
}

/**
 * 战力评估的纯计算层：全部公式在此，与游戏环境解耦，供单元测试直接驱动。
 */
object FleetStrengthMath {

    const val OFFICER_WEIGHT: Float = 3f
    const val SKILL_WEIGHT: Float = 4f
    const val P_MIN: Float = 0.85f
    const val P_MAX: Float = 2.2f
    const val EMA_ALPHA: Float = 0.3f

    /** 单艘舰船的评估输入。 */
    data class ShipInput(
        val deploymentPoints: Float,
        val sModCount: Int,
        val dModCount: Int,
        val phaseOrAutomated: Boolean,
        val civilian: Boolean,
    )

    /** 一次评估的全部输入。 */
    data class Inputs(
        val ships: List<ShipInput>,
        val officerLevels: List<Int>,
        val combatSkillCount: Int,
    ) {
        /** 未经平滑的原始战力分。 */
        val rawScore: Float
            get() = ships.sumOf { (it.deploymentPoints * qualityCoefficient(it)).toDouble() }.toFloat() +
                officerLevels.sum() * OFFICER_WEIGHT +
                combatSkillCount * SKILL_WEIGHT
    }

    /**
     * 品级系数 q：s-mod +0.08 / d-mod -0.06 / 相位或自动化 +0.10，封顶 [0.5, 1.5]；
     * 民用/后勤舰在加性修正后整体 ×0.5（同样受封顶约束）。
     */
    fun qualityCoefficient(ship: ShipInput): Float {
        var q = 1f + ship.sModCount * 0.08f - ship.dModCount * 0.06f +
            (if (ship.phaseOrAutomated) 0.10f else 0f)
        if (ship.civilian) q *= 0.5f
        return q.coerceIn(0.5f, 1.5f)
    }

    /** EMA 平滑（α=0.3）：首次评估直接采用原值。 */
    fun smooth(raw: Float, previous: Float?): Float =
        previous?.let { it + EMA_ALPHA * (raw - it) } ?: raw

    /** 压缩：p = sqrt(S / S_ref)，封顶 [0.85, 2.2]。 */
    fun compress(score: Float, referenceFP: Float): Float =
        sqrt(score / referenceFP.coerceAtLeast(1f)).coerceIn(P_MIN, P_MAX)

    /** 由平滑后分数构建快照（含逐项分解）。 */
    fun buildSnapshot(inputs: Inputs, smoothedScore: Float, referenceFP: Float): StrengthSnapshot {
        val p = compress(smoothedScore, referenceFP)
        val k = ((p - P_MIN) / (P_MAX - P_MIN)).coerceIn(0f, 1f)
        val shipScore = inputs.ships.sumOf { (it.deploymentPoints * qualityCoefficient(it)).toDouble() }.toFloat()
        return StrengthSnapshot(
            score = smoothedScore,
            reference = referenceFP.coerceAtLeast(1f),
            p = p,
            k = k,
            breakdown = listOf(
                StrengthSnapshot.Component("ships", shipScore),
                StrengthSnapshot.Component("officers", inputs.officerLevels.sum() * OFFICER_WEIGHT),
                StrengthSnapshot.Component("skills", inputs.combatSkillCount * SKILL_WEIGHT),
                StrengthSnapshot.Component("raw", inputs.rawScore),
                StrengthSnapshot.Component("smoothed", smoothedScore),
            ),
        )
    }
}
