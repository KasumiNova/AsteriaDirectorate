package data.missions.piercing_lance_basic;

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

        api.setFleetTagline(FleetSide.PLAYER, "ASTD automation: piercing lance hybrid-mount/cycle/cone");
        api.setFleetTagline(FleetSide.ENEMY, "Automation cone cluster / enemy lance fleet");

        final FleetMemberAPI shipA = api.addToFleet(FleetSide.PLAYER, "onslaught_Standard", FleetMemberType.SHIP, false);
        clearAllWeaponSlots(shipA);
        shipA.getVariant().addWeapon("WS 019", ASTDInGameAutomationScenario.PL_WEAPON_ID);

        final FleetMemberAPI shipB = api.addToFleet(FleetSide.PLAYER, "champion_Assault", FleetMemberType.SHIP, false);
        clearAllWeaponSlots(shipB);
        shipB.getVariant().addWeapon("WS 008", ASTDInGameAutomationScenario.PL_WEAPON_ID);

        clearAllWeaponSlots(api.addToFleet(FleetSide.PLAYER, "enforcer_Balanced", FleetMemberType.SHIP, false));
        clearAllWeaponSlots(api.addToFleet(FleetSide.PLAYER, "enforcer_Balanced", FleetMemberType.SHIP, false));

        clearAllWeaponSlots(api.addToFleet(FleetSide.ENEMY, "enforcer_Balanced", FleetMemberType.SHIP, false));
        clearAllWeaponSlots(api.addToFleet(FleetSide.ENEMY, "enforcer_Balanced", FleetMemberType.SHIP, false));
        clearAllWeaponSlots(api.addToFleet(FleetSide.ENEMY, "enforcer_Balanced", FleetMemberType.SHIP, false));

        final FleetMemberAPI enemyLance = api.addToFleet(FleetSide.ENEMY, "champion_Assault", FleetMemberType.SHIP, false);
        clearAllWeaponSlots(enemyLance);
        enemyLance.getVariant().addWeapon("WS 008", ASTDInGameAutomationScenario.PL_WEAPON_ID);

        api.addBriefingItem("Deploy piercing lances and observe hybrid-mount/cycle/cone telemetry.");

        api.initMap(-9000f, 9000f, -6000f, 6000f);
        api.setBackgroundSpriteName("graphics/backgrounds/background2.jpg");
        api.addPlugin(new ASTDAutomationCombatPlugin());
    }
}
