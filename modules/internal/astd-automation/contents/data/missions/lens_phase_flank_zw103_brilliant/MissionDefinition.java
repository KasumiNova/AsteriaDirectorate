package data.missions.lens_phase_flank_zw103_brilliant;

import cn.kasuminova.astd.combat.effect.generic.ASTDAutomationCombatPlugin;
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario;
import com.fs.starfarer.api.fleet.FleetGoal;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.mission.FleetSide;
import com.fs.starfarer.api.mission.MissionDefinitionAPI;
import com.fs.starfarer.api.mission.MissionDefinitionPlugin;

public final class MissionDefinition implements MissionDefinitionPlugin {
    @Override
    public void defineMission(final MissionDefinitionAPI api) {
        api.initFleet(FleetSide.PLAYER, "ASTD", FleetGoal.ATTACK, false, 5);
        api.initFleet(FleetSide.ENEMY, "DRONE", FleetGoal.ATTACK, true, 5);

        api.setFleetTagline(FleetSide.PLAYER, "ASTD automation: gravity phase AI survival (ZW-103)");
        api.setFleetTagline(FleetSide.ENEMY, "Automation torpedo cruiser (brilliant)");

        api.addToFleet(FleetSide.PLAYER, ASTDInGameAutomationScenario.PF3_VARIANT_ID, FleetMemberType.SHIP, false);
        api.addToFleet(FleetSide.ENEMY, ASTDInGameAutomationScenario.PF4_ENEMY_VARIANT_ID, FleetMemberType.SHIP, false);

        api.addBriefingItem("Deploy yuan-luo carrier against torpedo brilliant; survive without shield-hug surfaces or torpedo hits.");

        api.initMap(-9000f, 9000f, -6000f, 6000f);
        api.setBackgroundSpriteName("graphics/backgrounds/background2.jpg");
        api.addPlugin(new ASTDAutomationCombatPlugin());
    }
}
