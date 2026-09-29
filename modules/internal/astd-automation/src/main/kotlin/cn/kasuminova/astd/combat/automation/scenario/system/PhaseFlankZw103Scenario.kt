package cn.kasuminova.astd.combat.automation.scenario.system

import cn.kasuminova.astd.combat.automation.base.AbstractPhaseFlankScenario
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario

/**
 * 相位绕后矩阵·茑萝航母场景（HEALTH 模式，敌舰同为统治者）。
 *
 * 航母走位天然绕行，验证相位节奏健康（观测窗内下潜/上浮各 ≥2，不憋死不卡潜）
 * 而非绕后幅度。
 */
class PhaseFlankZw103Scenario : AbstractPhaseFlankScenario() {
    override val scenarioId: String = ASTDInGameAutomationScenario.PF3_SCENARIO_ID
    override val playerHullId: String = ASTDInGameAutomationScenario.PF3_HULL_ID
    override val enemyHullId: String = ASTDInGameAutomationScenario.PF_ENEMY_HULL_ID
    override val mode: PfMode = PfMode.HEALTH

    override fun isEnabled(): Boolean = ASTDInGameAutomationScenario.isPhaseFlankZw103Enabled()
}
