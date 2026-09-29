package cn.kasuminova.astd.combat.automation.scenario.system

import cn.kasuminova.astd.combat.automation.base.AbstractPhaseFlankScenario
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario

/**
 * 相位绕后矩阵·高速对照组场景（舜华 vs 锤头）。
 *
 * 锤头级极速 90 高于低机动闸，验证绕后意图对高机动目标完全不布防
 * （PHASE_ATTACK_RUN 全程零帧），同时 60s 窗内至少下潜一次证明
 * 防御性相位仍正常运行（相位 AI 未停转）。
 */
class PhaseFlankControlScenario : AbstractPhaseFlankScenario() {
    override val scenarioId: String = ASTDInGameAutomationScenario.PFC_SCENARIO_ID
    override val playerHullId: String = ASTDInGameAutomationScenario.PF_HULL_ID
    override val enemyHullId: String = ASTDInGameAutomationScenario.PFC_ENEMY_HULL_ID
    override val mode: PfMode = PfMode.CONTROL_NO_FLANK

    override fun isEnabled(): Boolean = ASTDInGameAutomationScenario.isPhaseFlankControlEnabled()
}
