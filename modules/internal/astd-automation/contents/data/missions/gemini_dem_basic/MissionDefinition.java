package data.missions.gemini_dem_basic;

import cn.kasuminova.astd.combat.effect.generic.ASTDAutomationCombatPlugin;
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario;
import com.fs.starfarer.api.fleet.FleetGoal;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.mission.FleetSide;
import com.fs.starfarer.api.mission.MissionDefinitionAPI;
import com.fs.starfarer.api.mission.MissionDefinitionPlugin;

/**
 * Dev-only mission surface for gemini DEM launcher/pod in-game automation.
 *
 * 玩家征服者（清空全部槽位后 WS 019 中型导弹槽装双子星 DEM 发射器、WS 001 大型导弹槽装发射舱）；
 * 敌方一艘统治者级靶舰（齐射/DEM 打击/同步冲击/击落一枚观测，保留 stock 武备由插件逐帧缴械——
 * 实机判例：清空全部武器槽的舰船会被标记 nonCombat，applyDamage 全额无效）；
 * 一艘敌版征服者（ENEMY_SCALE 相位携带发射舱，破晓敌版同步观测）。
 * 敌方不生成旗舰（includeFlagship=false）：自动旗舰会作为游荡敌舰干扰目标锁定与同步判定证据。
 * 全部走 reserves + 插件手动 spawn（部署时机确定）；玩家舰身份由插件 setPlayerShipExternal 赋予。
 * 相位机：MOUNT → SALVO（R1：TrackAI 供目标 + DEM 接管 + payload 读数 R2 + 同步冲击）→
 * KILL_ONE（击落高爆弹头 → 无同步）→ POD（发射舱齐射/ammo 4）→ ENEMY_SCALE（破晓敌版同步）→ COMPLETED。
 */
public final class MissionDefinition implements MissionDefinitionPlugin {
    @Override
    public void defineMission(final MissionDefinitionAPI api) {
        api.initFleet(FleetSide.PLAYER, "ASTD", FleetGoal.ATTACK, false, 5);
        api.initFleet(FleetSide.ENEMY, "DRONE", FleetGoal.ATTACK, false, 5);

        api.setFleetTagline(FleetSide.PLAYER, "ASTD automation: gemini DEM salvo/sync strike");
        api.setFleetTagline(FleetSide.ENEMY, "Automation target fleet");

        final FleetMemberAPI player = api.addToFleet(FleetSide.PLAYER, "conquest_Standard", FleetMemberType.SHIP, false);
        clearAllWeaponSlots(player);
        player.getVariant().addWeapon("WS 019", ASTDInGameAutomationScenario.GD_LAUNCHER_WEAPON_ID);
        player.getVariant().addWeapon("WS 001", ASTDInGameAutomationScenario.GD_POD_WEAPON_ID);

        // 实机判例：dominator_Standard 变体不存在（addToFleet 静默落空，靶舰全程不在场）；
        // 现役变体为 dominator_Assault / dominator_Support 等（2026-07-29 烟测证实）。
        final FleetMemberAPI target = api.addToFleet(FleetSide.ENEMY, "dominator_Assault", FleetMemberType.SHIP, false);

        final FleetMemberAPI enemyCarrier = api.addToFleet(FleetSide.ENEMY, "conquest_Standard", FleetMemberType.SHIP, false);
        clearAllWeaponSlots(enemyCarrier);
        enemyCarrier.getVariant().addWeapon("WS 001", ASTDInGameAutomationScenario.GD_POD_WEAPON_ID);

        api.addBriefingItem("Deploy gemini DEM and observe salvo/sync/emp-arc telemetry.");

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
