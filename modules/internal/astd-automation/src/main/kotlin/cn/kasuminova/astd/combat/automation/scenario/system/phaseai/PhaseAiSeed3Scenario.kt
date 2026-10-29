package cn.kasuminova.astd.combat.automation.scenario.system.phaseai

import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario

class PhaseAiSeed3Scenario : AbstractPhaseAiSeedScenario() {
    override val scenarioId = ASTDInGameAutomationScenario.PHASE_AI_SEED_3_ID
    override val seed: Long = 3L * 7919L + 31L
    override fun isEnabled() = ASTDInGameAutomationScenario.isPhaseAiSeed3Enabled()
}
