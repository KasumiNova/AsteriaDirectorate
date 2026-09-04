package data.missions.positron_shockwave_basic;

import cn.kasuminova.astd.combat.effect.generic.ASTDAutomationCombatPlugin;
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario;
import com.fs.starfarer.api.fleet.FleetGoal;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.mission.FleetSide;
import com.fs.starfarer.api.mission.MissionDefinitionAPI;
import com.fs.starfarer.api.mission.MissionDefinitionPlugin;

/**
 * Dev-only mission surface for positron shockwave in-game automation.
 *
 * 玩家野狼（清空全部槽位后 WS 001 小型能量槽装正电子冲击波）对一艘无武装警戒级靶舰：
 * 穿舰相位靶舰置 400su 弹道上（无触碰体积证据），波及相位移至 700su（满射程自爆锥面波及证据），
 * 近炸相位由插件投喂鱼叉导弹群（近炸引爆成片清除证据）。
 * 两舰均走 reserves + 插件手动 spawn（部署时机确定）；玩家舰身份由插件 setPlayerShipExternal 赋予。
 * 相位机：MOUNT → PASS_THROUGH（穿舰不爆 + 满射程自爆 ≈600）→ SPLASH（舰船蹭波及不触发近炸）
 * → FUSE（近炸成片清除 + devMode 浮字 + 锥面 VFX）→ COMPLETED。
 */
public final class MissionDefinition implements MissionDefinitionPlugin {
    @Override
    public void defineMission(final MissionDefinitionAPI api) {
        api.initFleet(FleetSide.PLAYER, "ASTD", FleetGoal.ATTACK, false, 5);
        api.initFleet(FleetSide.ENEMY, "DRONE", FleetGoal.ATTACK, true, 5);

        api.setFleetTagline(FleetSide.PLAYER, "ASTD automation: positron shockwave proximity fuse");
        api.setFleetTagline(FleetSide.ENEMY, "Automation target fleet");

        final FleetMemberAPI player = api.addToFleet(FleetSide.PLAYER, "wolf_Starting", FleetMemberType.SHIP, false);
        clearAllWeaponSlots(player);
        player.getVariant().addWeapon("WS 001", ASTDInGameAutomationScenario.PS_WEAPON_ID);

        final FleetMemberAPI target = api.addToFleet(FleetSide.ENEMY, "vigilance_Standard", FleetMemberType.SHIP, false);
        clearAllWeaponSlots(target);

        api.addBriefingItem("Deploy positron shockwave and observe fuse/max-range/splash telemetry.");

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
