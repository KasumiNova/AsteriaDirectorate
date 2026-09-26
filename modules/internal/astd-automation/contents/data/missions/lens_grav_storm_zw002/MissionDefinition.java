package data.missions.lens_grav_storm_zw002;

import cn.kasuminova.astd.combat.effect.generic.ASTDAutomationCombatPlugin;
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario;
import com.fs.starfarer.api.fleet.FleetGoal;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.mission.FleetSide;
import com.fs.starfarer.api.mission.MissionDefinitionAPI;
import com.fs.starfarer.api.mission.MissionDefinitionPlugin;

/**
 * Dev-only mission surface for gravity storm generator (ZW-002) in-game automation.
 * <p>
 * 玩家密蒙级（清空全部非内置武器槽，内置船插引力电磁力场/纳米修复协议不动）；
 * 敌方统治者级突击型（清空武器槽，皮实巡洋舰做力场压制/电弧/强制过载靶舰，
 * 靠插件 stabilize 钉在母舰正前方 600su 锥内满效区）。全走 reserves 由插件手动
 * spawn（玩家单舰走 vanilla 静默 deployAll，插件判重跳过），玩家舰身份由插件
 * setPlayerShipExternal 赋予。相位机验证：SPAWN → FIELD_OBSERVE（力场满效压制）
 * → ACTIVATE（useSystem 点火 / 充能代价+减伤）→ RELEASE（满充能释放：电弧结算
 * + 强制过载 + 激活期力场不失效）→ COOLDOWN_FIELD_OFF（冷却后力场收口复原）。
 */
public final class MissionDefinition implements MissionDefinitionPlugin {
    private static void clearNonBuiltInWeaponSlots(final FleetMemberAPI member) {
        for (final String slotId : member.getVariant().getFittedWeaponSlots()) {
            // 内置武器槽不可清空，仅清装配槽（范式同 lens_grav_rift_zw103）。
            if (member.getHullSpec().getBuiltInWeapons().containsKey(slotId)) {
                continue;
            }
            member.getVariant().clearSlot(slotId);
        }
    }

    @Override
    public void defineMission(final MissionDefinitionAPI api) {
        api.initFleet(FleetSide.PLAYER, "ASTD", FleetGoal.ATTACK, false, 5);
        api.initFleet(FleetSide.ENEMY, "DRONE", FleetGoal.ATTACK, true, 5);

        api.setFleetTagline(FleetSide.PLAYER, "ASTD automation: gravity storm generator field/release");
        api.setFleetTagline(FleetSide.ENEMY, "Automation storm target cruiser");

        final FleetMemberAPI player = api.addToFleet(FleetSide.PLAYER, ASTDInGameAutomationScenario.GS_VARIANT_ID, FleetMemberType.SHIP, false);
        clearNonBuiltInWeaponSlots(player);

        // 力场/电弧/过载靶舰：统治者级突击型（清空武器槽保留舰体，范式同 lens_grav_rift_zw103 的统治者）。
        final FleetMemberAPI target = api.addToFleet(FleetSide.ENEMY, "dominator_Assault", FleetMemberType.SHIP, false);
        clearNonBuiltInWeaponSlots(target);

        api.addBriefingItem("Deploy mi-meng and observe grav-em-field / charge / storm-release telemetry.");

        api.initMap(-9000f, 9000f, -6000f, 6000f);
        api.setBackgroundSpriteName("graphics/backgrounds/background2.jpg");
        api.addPlugin(new ASTDAutomationCombatPlugin());
    }
}
