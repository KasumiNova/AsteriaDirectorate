package data.missions.cuifeng_torpedo_basic;

import cn.kasuminova.astd.combat.effect.generic.ASTDAutomationCombatPlugin;
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario;
import com.fs.starfarer.api.fleet.FleetGoal;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.mission.FleetSide;
import com.fs.starfarer.api.mission.MissionDefinitionAPI;
import com.fs.starfarer.api.mission.MissionDefinitionPlugin;

public final class MissionDefinition implements MissionDefinitionPlugin {
    private static void clearAllWeaponSlots(final FleetMemberAPI member) {
        for (final String slotId : member.getVariant().getFittedWeaponSlots()) {
            member.getVariant().clearSlot(slotId);
        }
    }

    @Override
    public void defineMission(final MissionDefinitionAPI api) {
        api.initFleet(FleetSide.PLAYER, "ASTD", FleetGoal.ATTACK, false, 5);
        api.initFleet(FleetSide.ENEMY, "DRONE", FleetGoal.ATTACK, true, 5);

        api.setFleetTagline(FleetSide.PLAYER, "ASTD automation: cuifeng torpedo adaptive/hard-flux/aoe");
        api.setFleetTagline(FleetSide.ENEMY, "Automation shielded cruiser / frigate target fleet");

        final FleetMemberAPI player = api.addToFleet(FleetSide.PLAYER, "gryphon_Standard", FleetMemberType.SHIP, false);
        clearAllWeaponSlots(player);
        player.getVariant().addWeapon("WS 008", ASTDInGameAutomationScenario.CUIFENG_LAUNCHER_WEAPON_ID);
        player.getVariant().addWeapon("WS 010", ASTDInGameAutomationScenario.CUIFENG_TORPEDO_WEAPON_ID);

        final FleetMemberAPI cruiser = api.addToFleet(FleetSide.ENEMY, "eagle_Assault", FleetMemberType.SHIP, false);
        clearAllWeaponSlots(cruiser);

        final FleetMemberAPI frigate = api.addToFleet(FleetSide.ENEMY, "hound_Standard", FleetMemberType.SHIP, false);
        clearAllWeaponSlots(frigate);

        api.addBriefingItem("Deploy cuifeng torpedoes and observe adaptive/hard-flux/aoe telemetry.");

        api.initMap(-9000f, 9000f, -6000f, 6000f);
        api.setBackgroundSpriteName("graphics/backgrounds/background2.jpg");
        api.addPlugin(new ASTDAutomationCombatPlugin());
    }
}
