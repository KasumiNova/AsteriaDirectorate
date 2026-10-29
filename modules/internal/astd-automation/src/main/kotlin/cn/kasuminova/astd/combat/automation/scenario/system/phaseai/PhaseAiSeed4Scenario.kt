package cn.kasuminova.astd.combat.automation.scenario.system.phaseai

import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario

class PhaseAiSeed4Scenario : AbstractPhaseAiSeedScenario() {
    override val scenarioId = ASTDInGameAutomationScenario.PHASE_AI_SEED_4_ID
    override val seed: Long = 4L * 7919L + 31L
    override fun isEnabled() = ASTDInGameAutomationScenario.isPhaseAiSeed4Enabled()
}
