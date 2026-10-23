package data.missions.qiongjue_railgun_basic;

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

    private static void stripAllHullMods(final FleetMemberAPI member) {
        for (final String mod : new java.util.ArrayList<String>(member.getVariant().getHullMods())) {
            member.getVariant().removeMod(mod);
        }
    }

    @Override
    public void defineMission(final MissionDefinitionAPI api) {
        api.initFleet(FleetSide.PLAYER, "ASTD", FleetGoal.ATTACK, false, 5);
        api.initFleet(FleetSide.ENEMY, "DRONE", FleetGoal.ATTACK, true, 5);

        api.setFleetTagline(FleetSide.PLAYER, "ASTD automation: qiongjue phase railgun calc stacking");
        api.setFleetTagline(FleetSide.ENEMY, "Automation target fleet");

        final FleetMemberAPI player = api.addToFleet(FleetSide.PLAYER, "dominator_Assault", FleetMemberType.SHIP, false);
        clearAllWeaponSlots(player);
        stripAllHullMods(player);
        player.getVariant().addWeapon("WS 012", ASTDInGameAutomationScenario.QJ_WEAPON_ID);
        player.getVariant().addWeapon("WS 013", ASTDInGameAutomationScenario.QJ_WEAPON_ID);

        final FleetMemberAPI enemy = api.addToFleet(FleetSide.ENEMY, "dominator_Assault", FleetMemberType.SHIP, false);
        clearAllWeaponSlots(enemy);
        stripAllHullMods(enemy);
        enemy.getVariant().addWeapon("WS 012", ASTDInGameAutomationScenario.QJ_WEAPON_ID);

        final FleetMemberAPI switchTarget = api.addToFleet(FleetSide.ENEMY, "vigilance_Standard", FleetMemberType.SHIP, false);
        clearAllWeaponSlots(switchTarget);

        final FleetMemberAPI killTarget = api.addToFleet(FleetSide.ENEMY, "vigilance_Standard", FleetMemberType.SHIP, false);
        clearAllWeaponSlots(killTarget);

        api.addBriefingItem("Deploy qiongjue phase railgun and observe stacking/switch/decay/scaling telemetry.");

        api.initMap(-9000f, 9000f, -6000f, 6000f);
        api.setBackgroundSpriteName("graphics/backgrounds/background2.jpg");
        api.addPlugin(new ASTDAutomationCombatPlugin());
    }
}
