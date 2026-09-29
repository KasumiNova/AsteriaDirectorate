package cn.kasuminova.astd.combat.automation.scenario.system

import cn.kasuminova.astd.combat.automation.base.AbstractPhaseFlankScenario
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario

/**
 * 舜华相位绕后场景（两舰满装配 AI 对抗）。
 *
 * 验证 GravityPhaseCloakAI 对低机动目标的绕后意图：满装配舜华（AI 驾驶）对
 * 满装配统治者级（AI 驾驶），统计下潜次数、相位中绕敌舰的方位角扫描幅度与
 * 每次上浮时相对敌舰舰艏的方位差——修复前（PHASE_ATTACK_RUN 无人管理）舜华
 * 下潜后原地罚站，上浮方位差恒小。
 */
class PhaseFlankScenario : AbstractPhaseFlankScenario() {
    override val scenarioId: String = ASTDInGameAutomationScenario.PF_SCENARIO_ID
    override val playerHullId: String = ASTDInGameAutomationScenario.PF_HULL_ID
    override val enemyHullId: String = ASTDInGameAutomationScenario.PF_ENEMY_HULL_ID
    override val mode: PfMode = PfMode.FLANK

    override fun isEnabled(): Boolean = ASTDInGameAutomationScenario.isPhaseFlankScenarioEnabled()
}
