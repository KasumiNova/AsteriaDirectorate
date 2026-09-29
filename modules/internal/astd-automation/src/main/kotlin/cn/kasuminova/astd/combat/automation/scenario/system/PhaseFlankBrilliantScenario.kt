package cn.kasuminova.astd.combat.automation.scenario.system

import cn.kasuminova.astd.combat.automation.base.AbstractPhaseFlankScenario
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario

/**
 * 相位对抗·茑萝航母 vs 辉煌级场景（SURVIVAL 模式，敌舰台风鱼雷发射架满挂）。
 *
 * 判据化用户实机两症状：贴盾上浮（上浮落点间距低于贴脸线）与非相位硬吃高威胁
 * 投射物（reaper 级）任一发生即 Failed；生存窗 60s 内下潜 ≥2 且零违规即 Completed。
 */
class PhaseFlankBrilliantScenario : AbstractPhaseFlankScenario() {
    override val scenarioId: String = ASTDInGameAutomationScenario.PF4_SCENARIO_ID
    override val playerHullId: String = ASTDInGameAutomationScenario.PF3_HULL_ID
    override val enemyHullId: String = ASTDInGameAutomationScenario.PF4_ENEMY_HULL_ID
    override val mode: PfMode = PfMode.SURVIVAL

    override fun isEnabled(): Boolean = ASTDInGameAutomationScenario.isPhaseFlankBrilliantEnabled()
}
