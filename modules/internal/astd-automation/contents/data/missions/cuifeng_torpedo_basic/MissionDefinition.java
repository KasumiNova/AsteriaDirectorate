package data.missions.cuifeng_torpedo_basic;

import cn.kasuminova.astd.combat.effect.generic.ASTDAutomationCombatPlugin;
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario;
import com.fs.starfarer.api.fleet.FleetGoal;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.mission.FleetSide;
import com.fs.starfarer.api.mission.MissionDefinitionAPI;
import com.fs.starfarer.api.mission.MissionDefinitionPlugin;

/**
 * Dev-only mission surface for cuifeng torpedo in-game automation.
 * <p>
 * 玩家狮鹫级（清空全部槽位后中型导弹槽 WS 008 装摧锋鱼雷发射器、小型导弹槽 WS 010 装摧锋鱼雷）；
 * 敌方猎鹰级攻击型（清空全部槽位，盾常开：护盾命中硬辐推进观测面）+ 敌方猎犬级
 * （清空全部槽位，无盾护卫舰贴身巡洋舰：150su 全额面板 AOE 连带观测面）。
 * 全走 reserves 由插件手动 spawn，玩家舰身份由插件 setPlayerShipExternal 赋予。
 * 相位机验证：装配 → 打击（反舰目标选择 + 二段式调速器 + 自适应增伤 + 硬辐推进 + AOE + 十字辉星/星云）。
 */
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

        api.setFleetTagline(FleetSide.PLAYER, "ASTD automation: cuifeng torpedo adaptive/hard-flux/aoe");
        api.setFleetTagline(FleetSide.ENEMY, "Automation shielded cruiser / frigate target fleet");

        final FleetMemberAPI player = api.addToFleet(FleetSide.PLAYER, "gryphon_Standard", FleetMemberType.SHIP, false);
        clearAllWeaponSlots(player);
        player.getVariant().addWeapon("WS 008", ASTDInGameAutomationScenario.CUIFENG_LAUNCHER_WEAPON_ID);
        player.getVariant().addWeapon("WS 010", ASTDInGameAutomationScenario.CUIFENG_TORPEDO_WEAPON_ID);

        // 硬辐推进观测面：带盾巡洋舰（插件盾常开）。
        final FleetMemberAPI cruiser = api.addToFleet(FleetSide.ENEMY, "eagle_Assault", FleetMemberType.SHIP, false);
        clearAllWeaponSlots(cruiser);

        // AOE 连带观测面：无盾护卫舰贴身巡洋舰（150su 全额面板 AOE）。
        final FleetMemberAPI frigate = api.addToFleet(FleetSide.ENEMY, "hound_Standard", FleetMemberType.SHIP, false);
        clearAllWeaponSlots(frigate);

        api.addBriefingItem("Deploy cuifeng torpedoes and observe adaptive/hard-flux/aoe telemetry.");

        api.initMap(-9000f, 9000f, -6000f, 6000f);
        api.setBackgroundSpriteName("graphics/backgrounds/background2.jpg");
        api.addPlugin(new ASTDAutomationCombatPlugin());
    }
}
