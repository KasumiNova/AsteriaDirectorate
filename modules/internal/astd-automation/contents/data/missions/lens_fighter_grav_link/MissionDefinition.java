package data.missions.lens_fighter_grav_link;

import cn.kasuminova.astd.combat.effect.generic.ASTDAutomationCombatPlugin;
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario;
import com.fs.starfarer.api.fleet.FleetGoal;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.mission.FleetSide;
import com.fs.starfarer.api.mission.MissionDefinitionAPI;
import com.fs.starfarer.api.mission.MissionDefinitionPlugin;

/**
 * Dev-only mission surface for fighter grav link in-game automation.
 * <p>
 * 玩家飞蓬级（清空全部非内置武器槽，保留 3 甲板联队与内置装饰炮 WS0012 bloom，
 * 内置船插维度折叠甲板/纳米修复协议不动）；敌方秃鹰级攻击型（清空武器槽保留机库，
 * 双阔剑联队作战机陪练目标源）。全走 reserves 由插件手动 spawn（玩家单舰走
 * vanilla 静默 deployAll，插件判重跳过），玩家舰身份由插件 setPlayerShipExternal 赋予。
 * 相位机验证：SPAWN → WAIT_WINGS（扩容断言）→ ACTIVATE（useSystem 驱动）
 * → OBSERVE_ACTIVE（时流/减伤/软辐能）→ WAIT_RECALL（召回/软硬转化）→ RELAUNCH（重出击）。
 */
public final class MissionDefinition implements MissionDefinitionPlugin {
    private static void clearNonBuiltInWeaponSlots(final FleetMemberAPI member) {
        for (final String slotId : member.getVariant().getFittedWeaponSlots()) {
            // 内置武器槽（如飞蓬 WS0012 装饰炮）不可清空，仅清装配槽。
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

        api.setFleetTagline(FleetSide.PLAYER, "ASTD automation: fighter grav link deck/recall");
        api.setFleetTagline(FleetSide.ENEMY, "Automation sparring fighter carrier");

        final FleetMemberAPI player = api.addToFleet(FleetSide.PLAYER, ASTDInGameAutomationScenario.FGL_VARIANT_ID, FleetMemberType.SHIP, false);
        clearNonBuiltInWeaponSlots(player);

        // 战机陪练目标源：双阔剑联队（清空武器槽保留机库，范式同 stellar_mrm_basic 的秃鹰）。
        final FleetMemberAPI carrier = api.addToFleet(FleetSide.ENEMY, "condor_Attack", FleetMemberType.SHIP, false);
        clearNonBuiltInWeaponSlots(carrier);

        api.addBriefingItem("Deploy feipeng and observe folding-deck / grav-link recall telemetry.");

        api.initMap(-9000f, 9000f, -6000f, 6000f);
        api.setBackgroundSpriteName("graphics/backgrounds/background2.jpg");
        api.addPlugin(new ASTDAutomationCombatPlugin());
    }
}
