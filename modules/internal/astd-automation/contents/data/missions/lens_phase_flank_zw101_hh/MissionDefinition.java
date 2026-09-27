package data.missions.lens_phase_flank_zw101_hh;

import cn.kasuminova.astd.combat.effect.generic.ASTDAutomationCombatPlugin;
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario;
import com.fs.starfarer.api.fleet.FleetGoal;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.mission.FleetSide;
import com.fs.starfarer.api.mission.MissionDefinitionAPI;
import com.fs.starfarer.api.mission.MissionDefinitionPlugin;

/**
 * Dev-only mission surface for gravity phase cloak flank AI high-speed control (ZW-101 vs Hammerhead).
 * <p>
 * 对照组：锤头级（极速 90）机动力高于舜华（70），绕后意图不应对高速目标挂旗标——
 * CONTROL_NO_FLANK 判定：60s 观测窗内 PHASE_ATTACK_RUN 旗标帧数必须为 0 且至少
 * 下潜一次（证明相位 AI 在运行），出现旗标帧立即判 Failed。
 */
public final class MissionDefinition implements MissionDefinitionPlugin {
    @Override
    public void defineMission(final MissionDefinitionAPI api) {
        api.initFleet(FleetSide.PLAYER, "ASTD", FleetGoal.ATTACK, false, 5);
        api.initFleet(FleetSide.ENEMY, "DRONE", FleetGoal.ATTACK, true, 5);

        api.setFleetTagline(FleetSide.PLAYER, "ASTD automation: gravity phase flank AI control");
        api.setFleetTagline(FleetSide.ENEMY, "High-speed control target destroyer");

        api.addToFleet(FleetSide.PLAYER, ASTDInGameAutomationScenario.PF_VARIANT_ID, FleetMemberType.SHIP, false);
        api.addToFleet(FleetSide.ENEMY, ASTDInGameAutomationScenario.PFC_ENEMY_VARIANT_ID, FleetMemberType.SHIP, false);

        api.addBriefingItem("Deploy shun-hua vs hammerhead and verify zero attack-run flag frames within 60s.");

        api.initMap(-9000f, 9000f, -6000f, 6000f);
        api.setBackgroundSpriteName("graphics/backgrounds/background2.jpg");
        api.addPlugin(new ASTDAutomationCombatPlugin());
    }
}
