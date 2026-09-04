package data.missions.stellar_mrm_basic;

import cn.kasuminova.astd.combat.effect.generic.ASTDAutomationCombatPlugin;
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario;
import com.fs.starfarer.api.fleet.FleetGoal;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.mission.FleetSide;
import com.fs.starfarer.api.mission.MissionDefinitionAPI;
import com.fs.starfarer.api.mission.MissionDefinitionPlugin;

/**
 * Dev-only mission surface for stellar MRM in-game automation.
 *
 * 玩家狮鹫级（清空全部槽位后中型导弹槽 WS 008 装辉星发射舱、小型导弹槽 WS 010 装辉星发射器）；
 * 敌方秃鹰级攻击型（双阔剑联队，清空武器槽保留机库，猎机目标源）+ 敌方狮鹫级
 * （清空全部槽位后小型导弹槽 WS 010 装辉星发射器，敌版三档与撞击舰船目标）。
 * 全走 reserves 由插件手动 spawn，玩家舰身份由插件 setPlayerShipExternal 赋予。
 * 相位机验证：装配 → 优先追猎（首目标=战机 + 发射舱单次两发）→ 命中战机（增伤/EMP/逐武器电弧）
 * → 撞击舰船与护盾爆炸 → 撞线者死（低结构同归于尽 / 增压高结构仅爆炸）→ 敌版三档。
 */
public final class MissionDefinition implements MissionDefinitionPlugin {
    @Override
    public void defineMission(final MissionDefinitionAPI api) {
        api.initFleet(FleetSide.PLAYER, "ASTD", FleetGoal.ATTACK, false, 5);
        api.initFleet(FleetSide.ENEMY, "DRONE", FleetGoal.ATTACK, true, 5);

        api.setFleetTagline(FleetSide.PLAYER, "ASTD automation: stellar MRM hunt/emp/line-cross");
        api.setFleetTagline(FleetSide.ENEMY, "Automation fighter carrier / enemy mrm fleet");

        final FleetMemberAPI player = api.addToFleet(FleetSide.PLAYER, "gryphon_Standard", FleetMemberType.SHIP, false);
        clearAllWeaponSlots(player);
        player.getVariant().addWeapon("WS 008", ASTDInGameAutomationScenario.SM_POD_WEAPON_ID);
        player.getVariant().addWeapon("WS 010", ASTDInGameAutomationScenario.SM_LAUNCHER_WEAPON_ID);

        // 猎机目标源：双阔剑联队（每机 2×lightmg，战机全武器 EMP 电弧观测面）；清空武器槽保留机库。
        final FleetMemberAPI carrier = api.addToFleet(FleetSide.ENEMY, "condor_Attack", FleetMemberType.SHIP, false);
        clearAllWeaponSlots(carrier);

        // 撞击舰船目标 + 敌版携带者（敌版三档相位开火）。
        final FleetMemberAPI enemy = api.addToFleet(FleetSide.ENEMY, "gryphon_Standard", FleetMemberType.SHIP, false);
        clearAllWeaponSlots(enemy);
        enemy.getVariant().addWeapon("WS 010", ASTDInGameAutomationScenario.SM_LAUNCHER_WEAPON_ID);

        api.addBriefingItem("Deploy stellar MRMs and observe hunt/emp/line-cross telemetry.");

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
