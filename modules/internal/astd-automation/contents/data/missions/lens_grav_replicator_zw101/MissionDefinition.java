package data.missions.lens_grav_replicator_zw101;

import cn.kasuminova.astd.combat.effect.generic.ASTDAutomationCombatPlugin;
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario;
import com.fs.starfarer.api.fleet.FleetGoal;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.mission.FleetSide;
import com.fs.starfarer.api.mission.MissionDefinitionAPI;
import com.fs.starfarer.api.mission.MissionDefinitionPlugin;

/**
 * Dev-only mission surface for gravity space replicator / space folder (ZW-101) in-game automation.
 * <p>
 * 玩家舜华级（内置船插引力空间折跃器/纳米修复协议不动；清空装配槽后给 WS0001
 * 中型协同槽装一门原版脉冲激光 pulse_laser——能量实弹、非光束非装饰，正落复制器
 * 弹道复制口径；原装配的 astd_gcp8 为光束、电荷针刺为复合机制武器，均不利于
 * 原发/复制弹计数归因）；敌方统治者级突击型清空武器槽钉远场，仅作投喂弹体的
 * 敌对 source。全走 reserves 由插件手动 spawn（玩家单舰走 vanilla 静默 deployAll，
 * 插件判重跳过）。相位机验证：SPAWN（光束承伤 ×0.75）→ ACTIVATE（激活代价 +
 * 弹道复制 + 复制辐能尖峰）→ OBSERVE_COOLDOWN（冷却期减免复原 + 折跃停判）
 * → FOLD_FEED（折跃恢复：三态标记 + 镜像离场）。
 */
public final class MissionDefinition implements MissionDefinitionPlugin {
    private static void clearNonBuiltInWeaponSlots(final FleetMemberAPI member) {
        for (final String slotId : member.getVariant().getFittedWeaponSlots()) {
            // 内置武器槽（WS0004 装饰炮）不可清空，仅清装配槽。
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

        api.setFleetTagline(FleetSide.PLAYER, "ASTD automation: gravity replicator / space fold");
        api.setFleetTagline(FleetSide.ENEMY, "Automation fold-feed source cruiser");

        final FleetMemberAPI player = api.addToFleet(FleetSide.PLAYER, ASTDInGameAutomationScenario.GSR_VARIANT_ID, FleetMemberType.SHIP, false);
        clearNonBuiltInWeaponSlots(player);
        // 复制器观测武器：原版脉冲激光装 WS0001 中型协同槽（SYNERGY 兼容能量）。
        player.getVariant().addWeapon("WS0001", "pulselaser");

        // 投喂弹体敌对 source：统治者级突击型（清空武器槽保留舰体，钉远场不参与交战）。
        final FleetMemberAPI target = api.addToFleet(FleetSide.ENEMY, "dominator_Assault", FleetMemberType.SHIP, false);
        clearNonBuiltInWeaponSlots(target);

        api.addBriefingItem("Deploy shun-hua and observe replicator / space-fold telemetry.");

        api.initMap(-9000f, 9000f, -6000f, 6000f);
        api.setBackgroundSpriteName("graphics/backgrounds/background2.jpg");
        api.addPlugin(new ASTDAutomationCombatPlugin());
    }
}
