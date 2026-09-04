package data.missions.piercing_lance_basic;

import cn.kasuminova.astd.combat.effect.generic.ASTDAutomationCombatPlugin;
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario;
import com.fs.starfarer.api.fleet.FleetGoal;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.mission.FleetSide;
import com.fs.starfarer.api.mission.MissionDefinitionAPI;
import com.fs.starfarer.api.mission.MissionDefinitionPlugin;

/**
 * Dev-only mission surface for piercing lance in-game automation.
 *
 * 玩家方：onslaught（清空全部槽位后大型实弹炮塔 WS 019 装贯星之矛，HYBRID 实弹槽装配 +
 * 能量结算探针 + 循环/命中主测试射手）+ champion（清空后大型能量炮塔 WS 008 装贯星之矛，
 * HYBRID 能量槽装配证明 + 敌版相位锥面侧目标）+ 两艘 enforcer 僚舰（敌版相位锥面集群目标）。
 * 敌方：三艘 enforcer（清空槽位，命中单体/集群锥面靶标）+ champion（清空后 WS 008 装贯星之矛，
 * 敌版三档射手）。
 * 全走 reserves 由插件手动 spawn，玩家舰身份由插件 setPlayerShipExternal 赋予。
 * 相位机验证：装配（双槽 + 能量结算探针）→ 循环（2s 充能 + 5s 冷却 + 单体三层特效）
 * → 集群（锥面破片浮字 + 本体豁免）→ 敌版三档（锥面 80°/600su 放大）。
 */
public final class MissionDefinition implements MissionDefinitionPlugin {
    @Override
    public void defineMission(final MissionDefinitionAPI api) {
        api.initFleet(FleetSide.PLAYER, "ASTD", FleetGoal.ATTACK, false, 5);
        api.initFleet(FleetSide.ENEMY, "DRONE", FleetGoal.ATTACK, true, 5);

        api.setFleetTagline(FleetSide.PLAYER, "ASTD automation: piercing lance hybrid-mount/cycle/cone");
        api.setFleetTagline(FleetSide.ENEMY, "Automation cone cluster / enemy lance fleet");

        // 主测试射手：大型实弹槽装配（onslaught WS 019 为前向大型实弹炮塔，angle 0 / arc 150）。
        final FleetMemberAPI shipA = api.addToFleet(FleetSide.PLAYER, "onslaught_Standard", FleetMemberType.SHIP, false);
        clearAllWeaponSlots(shipA);
        shipA.getVariant().addWeapon("WS 019", ASTDInGameAutomationScenario.PL_WEAPON_ID);

        // 能量槽装配证明 + 敌版相位锥面侧目标（champion WS 008 为前向大型能量炮塔）。
        final FleetMemberAPI shipB = api.addToFleet(FleetSide.PLAYER, "champion_Assault", FleetMemberType.SHIP, false);
        clearAllWeaponSlots(shipB);
        shipB.getVariant().addWeapon("WS 008", ASTDInGameAutomationScenario.PL_WEAPON_ID);

        // 敌版相位锥面集群僚舰（玩家侧，吃敌版锥面波及）。
        clearAllWeaponSlots(api.addToFleet(FleetSide.PLAYER, "enforcer_Balanced", FleetMemberType.SHIP, false));
        clearAllWeaponSlots(api.addToFleet(FleetSide.PLAYER, "enforcer_Balanced", FleetMemberType.SHIP, false));

        // 命中单体/集群锥面靶标（敌方三艘 enforcer，清空槽位不反击）。
        clearAllWeaponSlots(api.addToFleet(FleetSide.ENEMY, "enforcer_Balanced", FleetMemberType.SHIP, false));
        clearAllWeaponSlots(api.addToFleet(FleetSide.ENEMY, "enforcer_Balanced", FleetMemberType.SHIP, false));
        clearAllWeaponSlots(api.addToFleet(FleetSide.ENEMY, "enforcer_Balanced", FleetMemberType.SHIP, false));

        // 敌版贯星射手（敌版三档相位开火）。
        final FleetMemberAPI enemyLance = api.addToFleet(FleetSide.ENEMY, "champion_Assault", FleetMemberType.SHIP, false);
        clearAllWeaponSlots(enemyLance);
        enemyLance.getVariant().addWeapon("WS 008", ASTDInGameAutomationScenario.PL_WEAPON_ID);

        api.addBriefingItem("Deploy piercing lances and observe hybrid-mount/cycle/cone telemetry.");

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
