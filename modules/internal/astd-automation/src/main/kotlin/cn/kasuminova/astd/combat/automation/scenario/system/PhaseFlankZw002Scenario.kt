package cn.kasuminova.astd.combat.automation.scenario.system

import cn.kasuminova.astd.combat.automation.base.AbstractPhaseFlankScenario
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario

/**
 * 相位绕后矩阵·密蒙巡洋场景（HEALTH 模式，敌舰同为统治者）。
 *
 * 巡洋舰体量更大、机动更钝，验证相位 AI 在巡洋舰体上的节奏健康：
 * 观测窗内下潜/上浮各 ≥2（不憋死不卡潜），不判绕后幅度。
 */
class PhaseFlankZw002Scenario : AbstractPhaseFlankScenario() {
    override val scenarioId: String = ASTDInGameAutomationScenario.PF2_SCENARIO_ID
    override val playerHullId: String = ASTDInGameAutomationScenario.PF2_HULL_ID
    override val enemyHullId: String = ASTDInGameAutomationScenario.PF_ENEMY_HULL_ID
    override val mode: PfMode = PfMode.HEALTH

    override fun isEnabled(): Boolean = ASTDInGameAutomationScenario.isPhaseFlankZw002Enabled()
}
