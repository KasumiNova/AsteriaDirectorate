package cn.kasuminova.astd.combat.automation.scenario.system.phaseai

import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario

class PhaseAiSeed6Scenario : AbstractPhaseAiSeedScenario() {
    override val scenarioId = ASTDInGameAutomationScenario.PHASE_AI_SEED_6_ID
    override val seed: Long = 6L * 7919L + 31L
    override fun isEnabled() = ASTDInGameAutomationScenario.isPhaseAiSeed6Enabled()
}
