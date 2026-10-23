package data.missions.gemini_dem_basic;

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
        api.initFleet(FleetSide.ENEMY, "DRONE", FleetGoal.ATTACK, false, 5);

        api.setFleetTagline(FleetSide.PLAYER, "ASTD automation: gemini DEM salvo/sync strike");
        api.setFleetTagline(FleetSide.ENEMY, "Automation target fleet");

        final FleetMemberAPI player = api.addToFleet(FleetSide.PLAYER, "conquest_Standard", FleetMemberType.SHIP, false);
        clearAllWeaponSlots(player);
        player.getVariant().addWeapon("WS 019", ASTDInGameAutomationScenario.GD_LAUNCHER_WEAPON_ID);
        player.getVariant().addWeapon("WS 001", ASTDInGameAutomationScenario.GD_POD_WEAPON_ID);

        final FleetMemberAPI target = api.addToFleet(FleetSide.ENEMY, "dominator_Assault", FleetMemberType.SHIP, false);

        final FleetMemberAPI enemyCarrier = api.addToFleet(FleetSide.ENEMY, "conquest_Standard", FleetMemberType.SHIP, false);
        clearAllWeaponSlots(enemyCarrier);
        enemyCarrier.getVariant().addWeapon("WS 001", ASTDInGameAutomationScenario.GD_POD_WEAPON_ID);

        api.addBriefingItem("Deploy gemini DEM and observe salvo/sync/emp-arc telemetry.");

        api.initMap(-9000f, 9000f, -6000f, 6000f);
        api.setBackgroundSpriteName("graphics/backgrounds/background2.jpg");
        api.addPlugin(new ASTDAutomationCombatPlugin());
    }
}
