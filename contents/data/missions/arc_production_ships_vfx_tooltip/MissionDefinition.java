package data.missions.arc_production_ships_vfx_tooltip;

import cn.kasuminova.astd.combat.effect.generic.ASTDAutomationCombatPlugin;
import com.fs.starfarer.api.fleet.FleetGoal;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.mission.FleetSide;
import com.fs.starfarer.api.mission.MissionDefinitionAPI;
import com.fs.starfarer.api.mission.MissionDefinitionPlugin;

/** Dev-only mission surface for ARC production ship VFX and tooltip automation. */
public final class MissionDefinition implements MissionDefinitionPlugin {
    @Override
    public void defineMission(final MissionDefinitionAPI api) {
        api.initFleet(FleetSide.PLAYER, "ASTD", FleetGoal.ATTACK, false, 5);
        api.initFleet(FleetSide.ENEMY, "DRONE", FleetGoal.ATTACK, true, 5);

        api.setFleetTagline(FleetSide.PLAYER, "ASTD automation: ARC production ships");
        api.setFleetTagline(FleetSide.ENEMY, "Automation target fleet");

        api.addToFleet(FleetSide.PLAYER, "astd_arc_jet_Standard", FleetMemberType.SHIP, true);
        api.addToFleet(FleetSide.PLAYER, "astd_plasma_arch_Standard", FleetMemberType.SHIP, false);
        api.addToFleet(FleetSide.PLAYER, "astd_radiation_belt_Standard", FleetMemberType.SHIP, false);
        api.addToFleet(FleetSide.PLAYER, "wolf_Assault", FleetMemberType.SHIP, false);
        api.addToFleet(FleetSide.PLAYER, "enforcer_Assault", FleetMemberType.SHIP, false);
        api.addToFleet(FleetSide.ENEMY, "onslaught_Standard", FleetMemberType.SHIP, false);

        api.defeatOnShipLoss("ASTD astd_arc_jet");
        api.addBriefingItem("Deploy ARC production ships and observe VFX/tooltip telemetry.");

        api.initMap(-9000f, 9000f, -6000f, 6000f);
        api.setBackgroundSpriteName("graphics/backgrounds/background2.jpg");
        api.addPlugin(new ASTDAutomationCombatPlugin());
    }
}
