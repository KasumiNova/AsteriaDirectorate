package data.missions.stellar_mrm_basic;

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

        api.setFleetTagline(FleetSide.PLAYER, "ASTD automation: stellar MRM hunt/emp/line-cross");
        api.setFleetTagline(FleetSide.ENEMY, "Automation fighter carrier / enemy mrm fleet");

        final FleetMemberAPI player = api.addToFleet(FleetSide.PLAYER, "gryphon_Standard", FleetMemberType.SHIP, false);
        clearAllWeaponSlots(player);
        player.getVariant().addWeapon("WS 008", ASTDInGameAutomationScenario.SM_POD_WEAPON_ID);
        player.getVariant().addWeapon("WS 010", ASTDInGameAutomationScenario.SM_LAUNCHER_WEAPON_ID);

        final FleetMemberAPI carrier = api.addToFleet(FleetSide.ENEMY, "condor_Attack", FleetMemberType.SHIP, false);
        clearAllWeaponSlots(carrier);

        final FleetMemberAPI enemy = api.addToFleet(FleetSide.ENEMY, "gryphon_Standard", FleetMemberType.SHIP, false);
        clearAllWeaponSlots(enemy);
        enemy.getVariant().addWeapon("WS 010", ASTDInGameAutomationScenario.SM_LAUNCHER_WEAPON_ID);

        api.addBriefingItem("Deploy stellar MRMs and observe hunt/emp/line-cross telemetry.");

        api.initMap(-9000f, 9000f, -6000f, 6000f);
        api.setBackgroundSpriteName("graphics/backgrounds/background2.jpg");
        api.addPlugin(new ASTDAutomationCombatPlugin());
    }
}
