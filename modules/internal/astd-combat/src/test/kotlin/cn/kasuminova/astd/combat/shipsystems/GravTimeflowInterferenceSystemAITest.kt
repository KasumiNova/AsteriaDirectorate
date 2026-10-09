package cn.kasuminova.astd.combat.shipsystems

import cn.kasuminova.astd.combat.shipsystems.GravTimeflowInterferenceSystemAI.Companion.AUTO_SCORE_WEIGHT
import cn.kasuminova.astd.combat.shipsystems.GravTimeflowInterferenceSystemAI.Companion.CandidateSnapshot
import cn.kasuminova.astd.combat.shipsystems.GravTimeflowInterferenceSystemAI.Companion.CARRIER_LOGISTICS_PENALTY
import cn.kasuminova.astd.combat.shipsystems.GravTimeflowInterferenceSystemAI.Companion.COMBAT_BONUS
import cn.kasuminova.astd.combat.shipsystems.GravTimeflowInterferenceSystemAI.Companion.DANGER_BONUS
import cn.kasuminova.astd.combat.shipsystems.GravTimeflowInterferenceSystemAI.Companion.DANGER_FLUX_LEVEL
import cn.kasuminova.astd.combat.shipsystems.GravTimeflowInterferenceSystemAI.Companion.FLAGSHIP_BONUS
import cn.kasuminova.astd.combat.shipsystems.GravTimeflowInterferenceSystemAI.Companion.OFFENSE_BONUS
import cn.kasuminova.astd.combat.shipsystems.GravTimeflowInterferenceSystemAI.Companion.dangerBonus
import cn.kasuminova.astd.combat.shipsystems.GravTimeflowInterferenceSystemAI.Companion.scoreCandidate
import cn.kasuminova.astd.combat.shipsystems.GravTimeflowInterferenceSystemAI.Companion.selectBest
import cn.kasuminova.astd.combat.shipsystems.GravTimeflowInterferenceSystemAI.Companion.sizeScore
import com.fs.starfarer.api.combat.ShipAPI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 引力时流干涉器 AI 选靶评分纯函数的行为覆盖：
 * 规格分 1:1 权重等价性、危险境地双条件门槛边界、总评分合成与惩罚乘区、
 * selectBest 选取语义（含纯航母降权被战斗舰压过）、空表 null。
 */
class GravTimeflowInterferenceSystemAITest {

    /** 基准快照：巡洋舰、部署点 20、自动分 20、低辐能、无危险、非旗舰、战斗类、无进攻意愿、非航母后勤。 */
    private fun baseline() = CandidateSnapshot(
        hullSize = ShipAPI.HullSize.CRUISER,
        deployPoints = 20f,
        autoScore = 20f,
        fluxLevel = 0.3f,
        inDanger = false,
        isPlayerFlagship = false,
        isCombat = true,
        offensiveIntent = false,
        carrierOrLogistics = false,
    )

    @Test
    fun `规格分舰级与部署点权重 1 比 1`() {
        // 权重 1:1：部署点增量与舰级升档增量对评分影响一致
        val tierUp = sizeScore(ShipAPI.HullSize.CRUISER, 10f) - sizeScore(ShipAPI.HullSize.DESTROYER, 10f)
        val dpUp = sizeScore(ShipAPI.HullSize.DESTROYER, 20f) - sizeScore(ShipAPI.HullSize.DESTROYER, 10f)
        assertEquals(tierUp, dpUp, 1e-4f)
        // 舰级单调：主力舰规格分高于护卫舰
        assertTrue(sizeScore(ShipAPI.HullSize.CAPITAL_SHIP, 0f) > sizeScore(ShipAPI.HullSize.FRIGATE, 0f))
    }

    @Test
    fun `危险境地加权需要高辐能与危险状态双条件`() {
        // 双条件成立
        assertTrue(dangerBonus(DANGER_FLUX_LEVEL, true) > 0f)
        // 辐能未达门槛不加权（边界下沿）
        assertEquals(0f, dangerBonus(DANGER_FLUX_LEVEL - 0.01f, true), 1e-4f)
        // 辐能达标但无危险状态不加权
        assertEquals(0f, dangerBonus(0.95f, false), 1e-4f)
    }

    @Test
    fun `总评分为各项加权之和乘降权乘区`() {
        val c = baseline().copy(
            fluxLevel = 0.9f,
            inDanger = true,
            isPlayerFlagship = true,
            offensiveIntent = true,
        )
        val expected = (sizeScore(c.hullSize, c.deployPoints) +
                DANGER_BONUS + FLAGSHIP_BONUS + COMBAT_BONUS +
                c.autoScore * AUTO_SCORE_WEIGHT + OFFENSE_BONUS)
        assertEquals(expected, scoreCandidate(c), 1e-4f)

        // 降权作用于加权总和
        val penalized = scoreCandidate(c.copy(carrierOrLogistics = true))
        assertEquals(expected * CARRIER_LOGISTICS_PENALTY, penalized, 1e-4f)
    }

    @Test
    fun `selectBest 取最高评分候选下标`() {
        val frigate = baseline().copy(
            hullSize = ShipAPI.HullSize.FRIGATE, deployPoints = 5f, autoScore = 5f,
        )
        val capital = baseline().copy(
            hullSize = ShipAPI.HullSize.CAPITAL_SHIP, deployPoints = 40f, autoScore = 40f,
        )
        val candidates = listOf(frigate, baseline(), capital)
        assertEquals(2, selectBest(candidates))
    }

    @Test
    fun `selectBest 大规格战斗舰压过纯航母`() {
        // 纯航母：规格分高但乘降权乘区
        val pureCarrier = baseline().copy(
            hullSize = ShipAPI.HullSize.CAPITAL_SHIP,
            deployPoints = 40f,
            autoScore = 30f,
            isCombat = false,
            carrierOrLogistics = true,
        )
        val combatCruiser = baseline()
        assertTrue(scoreCandidate(combatCruiser) > scoreCandidate(pureCarrier))
        assertEquals(1, selectBest(listOf(pureCarrier, combatCruiser)))
    }

    @Test
    fun `selectBest 空列表返回 null`() {
        assertNull(selectBest(emptyList()))
    }
}
