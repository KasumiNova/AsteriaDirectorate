package data.missions.charge_needle_basic;

import cn.kasuminova.astd.combat.effect.generic.ASTDAutomationCombatPlugin;
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario;
import com.fs.starfarer.api.fleet.FleetGoal;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.mission.FleetSide;
import com.fs.starfarer.api.mission.MissionDefinitionAPI;
import com.fs.starfarer.api.mission.MissionDefinitionPlugin;

/**
 * Dev-only mission surface for charge needle in-game automation.
 *
 * 玩家旗舰野狼（清空全部槽位后装小型 + 重型电荷针刺）对敌方伯劳鸟（小型电荷针刺）：
 * 护盾相位验证淤积叠层/维持乘区/安全闸，船体相位验证 EMP 泄放电弧与弹匣节奏，
 * 敌方针刺反向命中玩家验证受击方 negative HUD。
 */
public final class MissionDefinition implements MissionDefinitionPlugin {
    @Override
    public void defineMission(final MissionDefinitionAPI api) {
        api.initFleet(FleetSide.PLAYER, "ASTD", FleetGoal.ATTACK, false, 5);
        api.initFleet(FleetSide.ENEMY, "DRONE", FleetGoal.ATTACK, true, 5);

        api.setFleetTagline(FleetSide.PLAYER, "ASTD automation: charge needle stacking/discharge");
        api.setFleetTagline(FleetSide.ENEMY, "Automation target fleet");

        final FleetMemberAPI player = api.addToFleet(FleetSide.PLAYER, "wolf_Starting", FleetMemberType.SHIP, true);
        clearAllWeaponSlots(player);
        player.getVariant().addWeapon("WS 001", ASTDInGameAutomationScenario.CHARGE_NEEDLE_WEAPON_ID);
        player.getVariant().addWeapon("WS 004", ASTDInGameAutomationScenario.CHARGE_NEEDLE_HEAVY_WEAPON_ID);

        final FleetMemberAPI enemy = api.addToFleet(FleetSide.ENEMY, "shrike_Attack", FleetMemberType.SHIP, false);
        clearAllWeaponSlots(enemy);
        // 本环境 shrike_Attack 被 VariantAcknowledged 覆盖为 safetyoverrides + converted_hangar：
        // SO 射程压制公式 450+(700-450)*0.25=513 < 舞台间距 637，敌方 AutofireAI 判超程拒射（实机诊断证实）；
        // converted_hangar 放出的战机会污染舞台。烟测舞台移除两者。
        enemy.getVariant().removeMod("safetyoverrides");
        enemy.getVariant().removeMod("converted_hangar");
        enemy.getVariant().addWeapon("WS 000", ASTDInGameAutomationScenario.CHARGE_NEEDLE_WEAPON_ID);

        api.defeatOnShipLoss("ASTD wolf");
        api.addBriefingItem("Deploy charge needles and observe stacking/discharge telemetry.");

        api.initMap(-9000f, 9000f, -6000f, 6000f);
        api.setBackgroundSpriteName("graphics/backgrounds/background2.jpg");
        api.addPlugin(new ASTDAutomationCombatPlugin());
    }

    private static void clearAllWeaponSlots(final FleetMemberAPI member) {
        for (final String slotId : member.getVariant().getFittedWeaponSlots()) {
            member.getVariant().clearSlot(slotId);
        }
    }
}
