package data.missions.lens_phase_friendly_beam;

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

        api.setFleetTagline(FleetSide.PLAYER, "ASTD automation: phase friendly-fire defensive dive");
        api.setFleetTagline(FleetSide.ENEMY, "Distant dummy target cruiser");

        api.addToFleet(FleetSide.PLAYER, ASTDInGameAutomationScenario.PFB_VARIANT_ID, FleetMemberType.SHIP, false);
        api.addToFleet(FleetSide.PLAYER, ASTDInGameAutomationScenario.PFB_FRIENDLY_VARIANT_ID, FleetMemberType.SHIP, false);
        api.addToFleet(FleetSide.ENEMY, ASTDInGameAutomationScenario.PFB_ENEMY_VARIANT_ID, FleetMemberType.SHIP, false);

        api.addBriefingItem("Deploy shun-hua + friendly wolf firing real weapons; observe defensive dives.");

        api.initMap(-9000f, 9000f, -6000f, 6000f);
        api.setBackgroundSpriteName("graphics/backgrounds/background2.jpg");
        api.addPlugin(new ASTDAutomationCombatPlugin());
    }
}
