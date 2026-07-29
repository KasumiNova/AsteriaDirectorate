package data.missions.seven_stars_basic;

import cn.kasuminova.astd.combat.effect.generic.ASTDAutomationCombatPlugin;
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario;
import com.fs.starfarer.api.fleet.FleetGoal;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.mission.FleetSide;
import com.fs.starfarer.api.mission.MissionDefinitionAPI;
import com.fs.starfarer.api.mission.MissionDefinitionPlugin;

/**
 * Dev-only mission surface for seven stars teleport launcher in-game automation.
 *
 * 玩家奥德赛（清空全部槽位后 WS 001 大型能量槽装“七星”折跃发射器）；
 * 敌方两艘警戒级靶舰（BREAK 相位 600su 穿舰/断链观测 + TERMINAL 相位对舰终结观测，
 * 分相位部署避免互相污染证据）+ 一艘敌版奥德赛（ENEMY_MULTI 相位携带七星，破晓多段终结观测）。
 * 靶舰保留 stock 武备（由插件逐帧 hold fire 缴械）——实机判例：清空全部武器槽的舰船会被
 * 引擎标记 nonCombat（isNonCombat=true），applyDamage 对其全额无效，终结掉血证据拿不到。
 * 敌方不生成旗舰（includeFlagship=false）：自动旗舰会作为游荡敌舰成为折跃链
 * 「最近敌舰（无距离闸）」终结目标，污染 CHAIN 相位「无舰消散」证据。
 * 全部走 reserves + 插件手动 spawn（部署时机确定）；玩家舰身份由插件 setPlayerShipExternal 赋予。
 * 相位机：MOUNT → BREAK（未击杀断链消散 + 穿舰无触碰）→ CHAIN（连跳/上限 7/无舰消散 + 帧率）
 * → TERMINAL（单段 50% 无 EMP）→ ENEMY_MULTI（破晓敌版多段终结）→ COMPLETED。
 */
public final class MissionDefinition implements MissionDefinitionPlugin {
    @Override
    public void defineMission(final MissionDefinitionAPI api) {
        api.initFleet(FleetSide.PLAYER, "ASTD", FleetGoal.ATTACK, false, 5);
        // 敌方不生成旗舰（includeFlagship=false）：自动旗舰会作为游荡敌舰成为折跃链
        // 「最近敌舰（无距离闸）」终结目标，污染 CHAIN 相位「无舰消散」证据。
        api.initFleet(FleetSide.ENEMY, "DRONE", FleetGoal.ATTACK, false, 5);

        api.setFleetTagline(FleetSide.PLAYER, "ASTD automation: seven stars teleport chain");
        api.setFleetTagline(FleetSide.ENEMY, "Automation target fleet");

        final FleetMemberAPI player = api.addToFleet(FleetSide.PLAYER, "odyssey_Balanced", FleetMemberType.SHIP, false);
        clearAllWeaponSlots(player);
        player.getVariant().addWeapon("WS 001", ASTDInGameAutomationScenario.SS_WEAPON_ID);

        final FleetMemberAPI targetA = api.addToFleet(FleetSide.ENEMY, "vigilance_Standard", FleetMemberType.SHIP, false);

        final FleetMemberAPI targetB = api.addToFleet(FleetSide.ENEMY, "vigilance_Standard", FleetMemberType.SHIP, false);

        final FleetMemberAPI enemyCarrier = api.addToFleet(FleetSide.ENEMY, "odyssey_Balanced", FleetMemberType.SHIP, false);
        clearAllWeaponSlots(enemyCarrier);
        enemyCarrier.getVariant().addWeapon("WS 001", ASTDInGameAutomationScenario.SS_WEAPON_ID);

        api.addBriefingItem("Deploy seven stars and observe chain/terminal/dissipate telemetry.");

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
