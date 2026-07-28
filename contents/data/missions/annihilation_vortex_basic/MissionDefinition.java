package data.missions.annihilation_vortex_basic;

import cn.kasuminova.astd.combat.effect.generic.ASTDAutomationCombatPlugin;
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario;
import com.fs.starfarer.api.fleet.FleetGoal;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.mission.FleetSide;
import com.fs.starfarer.api.mission.MissionDefinitionAPI;
import com.fs.starfarer.api.mission.MissionDefinitionPlugin;

/**
 * Dev-only mission surface for annihilation vortex in-game automation.
 *
 * 玩家旗舰桑德级（清空全部槽位后大型能量槽 WS 003 装湮灭涡旋）+ 玩家侧奥德赛级
 * （大型协同槽 WS 001 同款，挂载验证与 COMPLETED 截图舞台）对敌方警戒级
 * （WS 001 lightac + WS 002 annihilatorpod，弹药投喂舰）与敌方奥德赛级（WS 001 同款，敌版三档）。
 * 相位机验证：双槽位装配 → 牵引/吸收 → 停火坍缩 → 空池保底 → 敌版三档 → 宿主死亡不坍缩。
 */
public final class MissionDefinition implements MissionDefinitionPlugin {
    @Override
    public void defineMission(final MissionDefinitionAPI api) {
        api.initFleet(FleetSide.PLAYER, "ASTD", FleetGoal.ATTACK, false, 5);
        api.initFleet(FleetSide.ENEMY, "DRONE", FleetGoal.ATTACK, true, 5);

        api.setFleetTagline(FleetSide.PLAYER, "ASTD automation: annihilation vortex absorb/collapse");
        api.setFleetTagline(FleetSide.ENEMY, "Automation feeder/target fleet");

        // 全部四舰均走 reserves + 插件手动 spawn（部署时机确定，不依赖原版旗舰自动部署相位）；
        // 玩家舰身份由插件 setPlayerShipExternal 赋予。
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

    private static void clearAllWeaponSlots(final FleetMemberAPI member) {
        for (final String slotId : member.getVariant().getFittedWeaponSlots()) {
            member.getVariant().clearSlot(slotId);
        }
    }
}
