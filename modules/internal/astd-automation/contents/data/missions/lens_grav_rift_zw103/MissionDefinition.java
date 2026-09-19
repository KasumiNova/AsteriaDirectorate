package data.missions.lens_grav_rift_zw103;

import cn.kasuminova.astd.combat.effect.generic.ASTDAutomationCombatPlugin;
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario;
import com.fs.starfarer.api.fleet.FleetGoal;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.mission.FleetSide;
import com.fs.starfarer.api.mission.MissionDefinitionAPI;
import com.fs.starfarer.api.mission.MissionDefinitionPlugin;

/**
 * Dev-only mission surface for gravity rift generator (ZW-103) in-game automation.
 *
 * 玩家茑萝级（清空全部非内置武器槽，保留 2 甲板联队与内置装饰炮 WS0007，
 * 内置船插引力相位甲板/纳米修复协议不动）；敌方统治者级突击型（清空武器槽，
 * 皮实巡洋舰做裂隙地雷近炸靶舰，靠插件 stabilize 钉住）。全走 reserves 由插件
 * 手动 spawn（玩家单舰走 vanilla 静默 deployAll，插件判重跳过），玩家舰身份由
 * 插件 setPlayerShipExternal 赋予。相位机验证：SPAWN → WAIT_WINGS（联队齐备）
 * → PHASE_LINK（setPhased 驱动战机联动相位与恢复）→ FLUX_RETURN（战机辐能返还）
 * → RIFT_FIRE（SYSTEM_TARGET_COORDS + useSystem 布雷 / 近炸结算）。
 */
public final class MissionDefinition implements MissionDefinitionPlugin {
    @Override
    public void defineMission(final MissionDefinitionAPI api) {
        api.initFleet(FleetSide.PLAYER, "ASTD", FleetGoal.ATTACK, false, 5);
        api.initFleet(FleetSide.ENEMY, "DRONE", FleetGoal.ATTACK, true, 5);

        api.setFleetTagline(FleetSide.PLAYER, "ASTD automation: gravity rift generator phase-link/rift");
        api.setFleetTagline(FleetSide.ENEMY, "Automation rift mine target cruiser");

        final FleetMemberAPI player = api.addToFleet(FleetSide.PLAYER, ASTDInGameAutomationScenario.GRG_VARIANT_ID, FleetMemberType.SHIP, false);
        clearNonBuiltInWeaponSlots(player);

        // 裂隙地雷近炸靶舰：统治者级突击型（清空武器槽保留舰体，范式同 gemini_dem_basic 的统治者）。
        final FleetMemberAPI target = api.addToFleet(FleetSide.ENEMY, "dominator_Assault", FleetMemberType.SHIP, false);
        clearNonBuiltInWeaponSlots(target);

        api.addBriefingItem("Deploy niao-luo and observe phase-link / flux-return / rift-mine telemetry.");

        api.initMap(-9000f, 9000f, -6000f, 6000f);
        api.setBackgroundSpriteName("graphics/backgrounds/background2.jpg");
        api.addPlugin(new ASTDAutomationCombatPlugin());
    }

    private static void clearNonBuiltInWeaponSlots(final FleetMemberAPI member) {
        for (final String slotId : member.getVariant().getFittedWeaponSlots()) {
            // 内置武器槽（如茑萝 WS0007 装饰炮）不可清空，仅清装配槽。
            if (member.getHullSpec().getBuiltInWeapons().containsKey(slotId)) continue;
            member.getVariant().clearSlot(slotId);
        }
    }
}
