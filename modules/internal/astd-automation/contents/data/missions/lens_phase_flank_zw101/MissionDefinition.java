package data.missions.lens_phase_flank_zw101;

import cn.kasuminova.astd.combat.effect.generic.ASTDAutomationCombatPlugin;
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario;
import com.fs.starfarer.api.fleet.FleetGoal;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.mission.FleetSide;
import com.fs.starfarer.api.mission.MissionDefinitionAPI;
import com.fs.starfarer.api.mission.MissionDefinitionPlugin;

/**
 * Dev-only mission surface for gravity phase cloak flank AI (ZW-101) in-game automation.
 * <p>
 * 玩家舜华级与敌方统治者级均满装配（不清槽）、全走 reserves 由插件手动 spawn，
 * 两舰都交给 AI 对抗（不调 setPlayerShipExternal）。插件逐帧回满两舰船体并
 * setDoNotEndCombat，追踪舜华相位状态边沿：下潜次数、相位中绕统治者的方位角
 * 扫描幅度、每次上浮时相对统治者舰艏的方位差——验证 GravityPhaseCloakAI 的
 * 绕后意图（PHASE_ATTACK_RUN 驱动走位穿透到侧后再上浮）。
 */
public final class MissionDefinition implements MissionDefinitionPlugin {
    @Override
    public void defineMission(final MissionDefinitionAPI api) {
        api.initFleet(FleetSide.PLAYER, "ASTD", FleetGoal.ATTACK, false, 5);
        api.initFleet(FleetSide.ENEMY, "DRONE", FleetGoal.ATTACK, true, 5);

        api.setFleetTagline(FleetSide.PLAYER, "ASTD automation: gravity phase flank AI");
        api.setFleetTagline(FleetSide.ENEMY, "Automation flank target cruiser");

        // 满装配对抗：相位 AI 需要真实火力与真实来袭才会走完整个决策链路。
        api.addToFleet(FleetSide.PLAYER, ASTDInGameAutomationScenario.PF_VARIANT_ID, FleetMemberType.SHIP, false);
        api.addToFleet(FleetSide.ENEMY, ASTDInGameAutomationScenario.PF_ENEMY_VARIANT_ID, FleetMemberType.SHIP, false);

        api.addBriefingItem("Deploy shun-hua and observe phase flank (attack-run / rear-arc surface) telemetry.");

        api.initMap(-9000f, 9000f, -6000f, 6000f);
        api.setBackgroundSpriteName("graphics/backgrounds/background2.jpg");
        api.addPlugin(new ASTDAutomationCombatPlugin());
    }
}
