package cn.kasuminova.astd.combat.automation.scenario.system.phaseai

import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario

class PhaseAiSeed1Scenario : AbstractPhaseAiSeedScenario() {
    override val scenarioId = ASTDInGameAutomationScenario.PHASE_AI_SEED_1_ID
    override val seed: Long = 1L * 7919L + 31L
    override fun isEnabled() = ASTDInGameAutomationScenario.isPhaseAiSeed1Enabled()
}
