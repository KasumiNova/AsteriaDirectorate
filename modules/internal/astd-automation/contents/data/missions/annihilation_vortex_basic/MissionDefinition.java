package data.missions.annihilation_vortex_basic;

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

        api.setFleetTagline(FleetSide.PLAYER, "ASTD automation: annihilation vortex absorb/collapse");
        api.setFleetTagline(FleetSide.ENEMY, "Automation feeder/target fleet");

        final FleetMemberAPI player = api.addToFleet(FleetSide.PLAYER, "sunder_Assault", FleetMemberType.SHIP, false);
        clearAllWeaponSlots(player);
        player.getVariant().addWeapon("WS 003", ASTDInGameAutomationScenario.AV_WEAPON_ID);

        final FleetMemberAPI synergy = api.addToFleet(FleetSide.PLAYER, "odyssey_Balanced", FleetMemberType.SHIP, false);
        clearAllWeaponSlots(synergy);
        synergy.getVariant().addWeapon("WS 001", ASTDInGameAutomationScenario.AV_WEAPON_ID);

        final FleetMemberAPI enemy = api.addToFleet(FleetSide.ENEMY, "odyssey_Balanced", FleetMemberType.SHIP, false);
        clearAllWeaponSlots(enemy);
        enemy.getVariant().addWeapon("WS 001", ASTDInGameAutomationScenario.AV_WEAPON_ID);

        final FleetMemberAPI feeder = api.addToFleet(FleetSide.ENEMY, "vigilance_Standard", FleetMemberType.SHIP, false);
        clearAllWeaponSlots(feeder);
        feeder.getVariant().addWeapon("WS 001", "lightac");
        feeder.getVariant().addWeapon("WS 002", "annihilatorpod");

        api.addBriefingItem("Deploy annihilation vortex and observe absorb/collapse/scaling telemetry.");

        api.initMap(-9000f, 9000f, -6000f, 6000f);
        api.setBackgroundSpriteName("graphics/backgrounds/background2.jpg");
        api.addPlugin(new ASTDAutomationCombatPlugin());
    }
}
