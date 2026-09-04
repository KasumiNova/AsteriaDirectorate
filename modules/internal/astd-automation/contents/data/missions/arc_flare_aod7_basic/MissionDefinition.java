package data.missions.arc_flare_aod7_basic;

import cn.kasuminova.astd.combat.effect.generic.ASTDAutomationCombatPlugin;
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario;
import com.fs.starfarer.api.fleet.FleetGoal;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.mission.FleetSide;
import com.fs.starfarer.api.mission.MissionDefinitionAPI;
import com.fs.starfarer.api.mission.MissionDefinitionPlugin;

/** Dev-only mission surface for Arc Flare + AOD-7 in-game VFX automation. */
public final class MissionDefinition implements MissionDefinitionPlugin {
    @Override
    public void defineMission(final MissionDefinitionAPI api) {
        api.initFleet(FleetSide.PLAYER, "ASTD", FleetGoal.ATTACK, false, 5);
        api.initFleet(FleetSide.ENEMY, "DRONE", FleetGoal.ATTACK, true, 5);

        api.setFleetTagline(FleetSide.PLAYER, "ASTD automation: Arc Flare AOD-7 VFX");
        api.setFleetTagline(FleetSide.ENEMY, "Automation target fleet");

        api.addToFleet(FleetSide.PLAYER, ASTDInGameAutomationScenario.VARIANT_ID, FleetMemberType.SHIP, true);
        api.addToFleet(FleetSide.ENEMY, "onslaught_Standard", FleetMemberType.SHIP, false);

        api.defeatOnShipLoss("ASTD " + ASTDInGameAutomationScenario.SHIP_ID);
        api.addBriefingItem("Deploy Arc Flare and observe AOD-7 projectile VFX telemetry.");

        api.initMap(-9000f, 9000f, -6000f, 6000f);
        api.setBackgroundSpriteName("graphics/backgrounds/background2.jpg");
        api.addPlugin(new ASTDAutomationCombatPlugin());
    }
}