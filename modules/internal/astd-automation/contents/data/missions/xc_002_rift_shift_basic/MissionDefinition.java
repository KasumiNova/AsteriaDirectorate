package data.missions.xc_002_rift_shift_basic;

import cn.kasuminova.astd.combat.effect.generic.ASTDAutomationCombatPlugin;
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario;
import com.fs.starfarer.api.fleet.FleetGoal;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.mission.FleetSide;
import com.fs.starfarer.api.mission.MissionDefinitionAPI;
import com.fs.starfarer.api.mission.MissionDefinitionPlugin;

/**
 * Dev-only mission surface for XC-002 淬刃 (rift shift / imaginary wings / starfall wing) in-game automation.
 * <p>
 * 玩家淬刃级（清空全部非内置武器槽，内置船插虚数之翼/纳米修复协议与内置主炮坠星残翼不动）；
 * 敌方统治者级突击型（清空武器槽，皮实巡洋舰做裂隙接触/闭合爆炸/主炮穿透靶舰，
 * 靠插件 stabilize 钉在折跃路径上）。全走 reserves 由插件手动 spawn
 * （范式同 lens_grav_storm_zw002），玩家舰身份由插件 setPlayerShipExternal 赋予。
 * 相位机验证：SPAWN → WINGS_OBSERVE（静止伤害乘区 −25%）→ SHIFT（useSystem 点火 /
 * 800su 位移 / 速度窗口峰值 / 裂隙接触掉血）→ CLOSURE（闭合爆炸掉血）→
 * WEAPON（主弹/子射弹供给登记 + 目标振频适应叠层 + 穿透掉血）。
 */
public final class MissionDefinition implements MissionDefinitionPlugin {
    private static void clearNonBuiltInWeaponSlots(final FleetMemberAPI member) {
        for (final String slotId : member.getVariant().getFittedWeaponSlots()) {
            // 内置武器槽不可清空，仅清装配槽（范式同 lens_grav_storm_zw002）。
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

        api.setFleetTagline(FleetSide.PLAYER, "ASTD automation: rift shift / imaginary wings / starfall wing");
        api.setFleetTagline(FleetSide.ENEMY, "Automation rift target cruiser");

        final FleetMemberAPI player = api.addToFleet(FleetSide.PLAYER, ASTDInGameAutomationScenario.XC002_VARIANT_ID, FleetMemberType.SHIP, false);
        clearNonBuiltInWeaponSlots(player);

        // 裂隙/主炮靶舰：统治者级突击型（清空武器槽保留舰体，范式同 lens_grav_storm_zw002 的统治者）。
        final FleetMemberAPI target = api.addToFleet(FleetSide.ENEMY, "dominator_Assault", FleetMemberType.SHIP, false);
        clearNonBuiltInWeaponSlots(target);

        api.addBriefingItem("Deploy XC-002 and observe rift-shift / imaginary-wings / starfall-wing telemetry.");

        api.initMap(-9000f, 9000f, -6000f, 6000f);
        api.setBackgroundSpriteName("graphics/backgrounds/background2.jpg");
        api.addPlugin(new ASTDAutomationCombatPlugin());
    }
}
