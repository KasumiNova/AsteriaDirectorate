package data.missions.electric_drive_basic;

import cn.kasuminova.astd.combat.effect.generic.ASTDAutomationCombatPlugin;
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario;
import com.fs.starfarer.api.fleet.FleetGoal;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.mission.FleetSide;
import com.fs.starfarer.api.mission.MissionDefinitionAPI;
import com.fs.starfarer.api.mission.MissionDefinitionPlugin;

/**
 * Dev-only mission surface for electric drive accelerator in-game automation.
 *
 * 玩家旗舰锤头级（清空全部槽位后中型实弹槽 WS 001 装电驱加速炮）对敌方锤头级（同款单装）：
 * 射程相位验证净空加速随辐能伸缩（0/30%/50% 三档读 weapon.range），
 * 开火相位验证每触发 8 弹（LINKED 双管 × burst 4）与装药追加伤害遥测，
 * 难度相位经 installScaleForTests 切 k_s 验证敌版三档射程与追加伤害幅度。
 */
public final class MissionDefinition implements MissionDefinitionPlugin {
    @Override
    public void defineMission(final MissionDefinitionAPI api) {
        api.initFleet(FleetSide.PLAYER, "ASTD", FleetGoal.ATTACK, false, 5);
        api.initFleet(FleetSide.ENEMY, "DRONE", FleetGoal.ATTACK, true, 5);

        api.setFleetTagline(FleetSide.PLAYER, "ASTD automation: electric drive accelerator range/charge");
        api.setFleetTagline(FleetSide.ENEMY, "Automation target fleet");

        final FleetMemberAPI player = api.addToFleet(FleetSide.PLAYER, "hammerhead_Balanced", FleetMemberType.SHIP, true);
        clearAllWeaponSlots(player);
        // 摘除目标定位系统（ITU）：其射程加成会抬升射程基线，破坏净空加速 800/900/1000 的期望读数。
        player.getVariant().removeMod("targetingunit");
        player.getVariant().addWeapon("WS 001", ASTDInGameAutomationScenario.EDA_WEAPON_ID);

        final FleetMemberAPI enemy = api.addToFleet(FleetSide.ENEMY, "hammerhead_Balanced", FleetMemberType.SHIP, false);
        clearAllWeaponSlots(enemy);
        enemy.getVariant().removeMod("targetingunit");
        enemy.getVariant().addWeapon("WS 001", ASTDInGameAutomationScenario.EDA_WEAPON_ID);

        api.defeatOnShipLoss("ASTD hammerhead");
        api.addBriefingItem("Deploy electric drive accelerator and observe range/charge telemetry.");

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
