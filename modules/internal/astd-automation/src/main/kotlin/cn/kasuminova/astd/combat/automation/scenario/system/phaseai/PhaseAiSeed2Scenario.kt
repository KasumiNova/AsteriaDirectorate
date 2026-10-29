package cn.kasuminova.astd.combat.automation.scenario.system.phaseai

import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario

class PhaseAiSeed2Scenario : AbstractPhaseAiSeedScenario() {
    override val scenarioId = ASTDInGameAutomationScenario.PHASE_AI_SEED_2_ID
    override val seed: Long = 2L * 7919L + 31L
    override fun isEnabled() = ASTDInGameAutomationScenario.isPhaseAiSeed2Enabled()
}
