package data.missions.lens_phase1_foundation;

import cn.kasuminova.astd.combat.effect.generic.ASTDAutomationCombatPlugin;
import com.fs.starfarer.api.fleet.FleetGoal;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.mission.FleetSide;
import com.fs.starfarer.api.mission.MissionDefinitionAPI;
import com.fs.starfarer.api.mission.MissionDefinitionPlugin;

/**
 * Dev-only mission surface for phase-1 gravitational lens automation evidence.
 *
 * Deploys the gravitational lens flagship (default crewed dual-mode), one ally to
 * provide a friendly ECM/intel-hub context, and one enemy so combat does not auto-end.
 */
public final class MissionDefinition implements MissionDefinitionPlugin {
    @Override
    public void defineMission(final MissionDefinitionAPI api) {
        api.initFleet(FleetSide.PLAYER, "ASTD", FleetGoal.ATTACK, false, 5);
        api.initFleet(FleetSide.ENEMY, "DRONE", FleetGoal.ATTACK, true, 5);

        api.setFleetTagline(FleetSide.PLAYER, "ASTD automation: gravitational lens phase-1");
        api.setFleetTagline(FleetSide.ENEMY, "Automation target fleet");

        api.addToFleet(FleetSide.PLAYER, "astd_gravitational_lens_Standard", FleetMemberType.SHIP, true);
        api.addToFleet(FleetSide.PLAYER, "enforcer_Assault", FleetMemberType.SHIP, false);
        api.addToFleet(FleetSide.ENEMY, "onslaught_Standard", FleetMemberType.SHIP, false);

        api.defeatOnShipLoss("ASTD astd_gravitational_lens");
        api.addBriefingItem("Deploy the gravitational lens and observe phase-1 foundation telemetry.");

        api.initMap(-9000f, 9000f, -6000f, 6000f);
        api.setBackgroundSpriteName("graphics/backgrounds/background2.jpg");
        api.addPlugin(new ASTDAutomationCombatPlugin());
    }
}
