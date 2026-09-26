package data.missions.ice_shard_mirv_basic;

import cn.kasuminova.astd.combat.effect.generic.ASTDAutomationCombatPlugin;
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario;
import com.fs.starfarer.api.fleet.FleetGoal;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.mission.FleetSide;
import com.fs.starfarer.api.mission.MissionDefinitionAPI;
import com.fs.starfarer.api.mission.MissionDefinitionPlugin;

/**
 * Dev-only mission surface for ice shard MIRV in-game automation.
 * <p>
 * 玩家狮鹫级（清空全部槽位后中型导弹槽 WS 008 装源生冰晶 MIRV 发射舱、小型导弹槽 WS 010
 * 装源生冰晶 MIRV）；敌方统治者级攻击型（清空全部槽位，盾常关：子冰晶命中舰体附着观测面）。
 * 子冰晶武器 astd_ice_shard_sub 为隐藏内部武器，无需装配。
 * 全走 reserves 由插件手动 spawn，玩家舰身份由插件 setPlayerShipExternal 赋予。
 * 相位机验证：装配 → 分裂（母弹引信注册 + 600su 分裂 15 枚子冰晶）→ 附着（5s 存续 0.5s 周期伤害）。
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

        api.setFleetTagline(FleetSide.PLAYER, "ASTD automation: ice shard MIRV split/attach");
        api.setFleetTagline(FleetSide.ENEMY, "Automation cruiser target fleet");

        final FleetMemberAPI player = api.addToFleet(FleetSide.PLAYER, "gryphon_Standard", FleetMemberType.SHIP, false);
        clearAllWeaponSlots(player);
        player.getVariant().addWeapon("WS 008", ASTDInGameAutomationScenario.ICE_SHARD_POD_WEAPON_ID);
        player.getVariant().addWeapon("WS 010", ASTDInGameAutomationScenario.ICE_SHARD_MIRV_WEAPON_ID);

        // 附着观测面：巡洋舰靶舰（插件盾常关，子冰晶命中舰体才附着）。
        final FleetMemberAPI target = api.addToFleet(FleetSide.ENEMY, "dominator_Assault", FleetMemberType.SHIP, false);
        clearAllWeaponSlots(target);

        api.addBriefingItem("Deploy ice shard MIRVs and observe split/attach telemetry.");

        api.initMap(-9000f, 9000f, -6000f, 6000f);
        api.setBackgroundSpriteName("graphics/backgrounds/background2.jpg");
        api.addPlugin(new ASTDAutomationCombatPlugin());
    }
}
