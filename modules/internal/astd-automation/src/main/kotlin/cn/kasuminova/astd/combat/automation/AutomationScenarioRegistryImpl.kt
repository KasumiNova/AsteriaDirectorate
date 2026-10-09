package cn.kasuminova.astd.combat.automation

import cn.kasuminova.astd.combat.automation.api.AutomationScenario
import cn.kasuminova.astd.combat.automation.api.AutomationScenarioRegistry
import cn.kasuminova.astd.combat.automation.scenario.arc.AnnihilationVortexScenario
import cn.kasuminova.astd.combat.automation.scenario.arc.ChargeNeedleScenario
import cn.kasuminova.astd.combat.automation.scenario.arc.ElectricDriveScenario
import cn.kasuminova.astd.combat.automation.scenario.arc.GeminiDemScenario
import cn.kasuminova.astd.combat.automation.scenario.arc.HeavyIonPulseScenario
import cn.kasuminova.astd.combat.automation.scenario.arc.PositronShockwaveScenario
import cn.kasuminova.astd.combat.automation.scenario.arc.QiongjueRailgunScenario
import cn.kasuminova.astd.combat.automation.scenario.arc.SevenStarsScenario
import cn.kasuminova.astd.combat.automation.scenario.lens.CuifengTorpedoScenario
import cn.kasuminova.astd.combat.automation.scenario.lens.IceShardMirvScenario
import cn.kasuminova.astd.combat.automation.scenario.lens.PiercingLanceScenario
import cn.kasuminova.astd.combat.automation.scenario.lens.StellarMrmScenario
import cn.kasuminova.astd.combat.automation.scenario.production.ArcProductionScenario
import cn.kasuminova.astd.combat.automation.scenario.system.FighterGravLinkScenario
import cn.kasuminova.astd.combat.automation.scenario.system.GravReplicatorScenario
import cn.kasuminova.astd.combat.automation.scenario.system.GravRiftScenario
import cn.kasuminova.astd.combat.automation.scenario.system.GravStormScenario
import cn.kasuminova.astd.combat.automation.scenario.system.PhaseFlankBrilliantScenario
import cn.kasuminova.astd.combat.automation.scenario.system.PhaseFlankControlScenario
import cn.kasuminova.astd.combat.automation.scenario.system.PhaseFlankScenario
import cn.kasuminova.astd.combat.automation.scenario.system.PhaseFlankZw002Scenario
import cn.kasuminova.astd.combat.automation.scenario.system.PhaseFlankZw103Scenario
import cn.kasuminova.astd.combat.automation.scenario.system.PhaseFriendlyBeamScenario
import cn.kasuminova.astd.combat.automation.scenario.xc.RiftShiftScenario
import cn.kasuminova.astd.combat.automation.scenario.xc.StarfallEchoScenario
import cn.kasuminova.astd.combat.automation.scenario.xc.TrailPauseProbeScenario

/**
 * [AutomationScenarioRegistry] 实现：登记全部战斗自动化场景处理器。
 *
 * 登记顺序与拆分前枢纽 init/advance/render 三条 if-else 链一致（谓词互斥，顺序本不影响命中，
 * 保持原序便于对账）；坠星残响为默认场景，登记在末尾兼作 [defaultHandler]。
 *
 * 新场景接入：新增 [AutomationScenario] 实现类后在 [handlers] 末尾（默认场景之前）登记一行。
 */
class AutomationScenarioRegistryImpl : AutomationScenarioRegistry {

    override val defaultHandler: AutomationScenario = StarfallEchoScenario()

    override val handlers: List<AutomationScenario> = listOf(
        GravRiftScenario(),
        FighterGravLinkScenario(),
        GravStormScenario(),
        GravReplicatorScenario(),
        PhaseFlankScenario(),
        PhaseFlankZw002Scenario(),
        PhaseFlankZw103Scenario(),
        PhaseFlankControlScenario(),
        PhaseFlankBrilliantScenario(),
        PhaseFriendlyBeamScenario(),
        RiftShiftScenario(),
        TrailPauseProbeScenario(),
        PiercingLanceScenario(),
        StellarMrmScenario(),
        CuifengTorpedoScenario(),
        IceShardMirvScenario(),
        GeminiDemScenario(),
        HeavyIonPulseScenario(),
        SevenStarsScenario(),
        PositronShockwaveScenario(),
        QiongjueRailgunScenario(),
        AnnihilationVortexScenario(),
        ElectricDriveScenario(),
        ChargeNeedleScenario(),
        ArcProductionScenario(),
        defaultHandler,
    )
}
