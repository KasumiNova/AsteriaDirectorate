package data.missions.qiongjue_railgun_basic;

import cn.kasuminova.astd.combat.effect.generic.ASTDAutomationCombatPlugin;
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario;
import com.fs.starfarer.api.fleet.FleetGoal;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.mission.FleetSide;
import com.fs.starfarer.api.mission.MissionDefinitionAPI;
import com.fs.starfarer.api.mission.MissionDefinitionPlugin;

/**
 * Dev-only mission surface for qiongjue phase railgun in-game automation.
 *
 * 玩家侧统治者级（清空全部槽位后两座大型实弹槽 WS 012/WS 013 各装一门穷距，验证同舰双穷距
 * 复合键隔离）对敌方统治者级（WS 012 单装穷距，敌版三档）+ 两艘无武装警戒级
 * （切换目标折算 / 打死目标转火不折算的靶舰）。
 * 全部五舰均走 reserves + 插件手动 spawn（部署时机确定）；玩家舰身份由插件 setPlayerShipExternal 赋予。
 * 相位机：MOUNT → STACK（叠层/伤害乘区/射速 spike）→ DUAL（双穷距独立）→ SWITCH（异目标折算）
 * → DECAY（3s 窗口衰减）→ KILL（打死转火不折算）→ ENEMY_SCALE（敌版三档）→ COMPLETED。
 */
public final class MissionDefinition implements MissionDefinitionPlugin {
    @Override
    public void defineMission(final MissionDefinitionAPI api) {
        api.initFleet(FleetSide.PLAYER, "ASTD", FleetGoal.ATTACK, false, 5);
        api.initFleet(FleetSide.ENEMY, "DRONE", FleetGoal.ATTACK, true, 5);

        api.setFleetTagline(FleetSide.PLAYER, "ASTD automation: qiongjue phase railgun calc stacking");
        api.setFleetTagline(FleetSide.ENEMY, "Automation target fleet");

        final FleetMemberAPI player = api.addToFleet(FleetSide.PLAYER, "dominator_Assault", FleetMemberType.SHIP, false);
        clearAllWeaponSlots(player);
        // 变体被 VariantAcknowledged 覆盖（targetingunit/ballistic_rangefinder 拉伸射程、converted_hangar 放 stray 战机），
        // 射程相位校验按基线 1200 断言，必须摘净全部外加 hullmod（EDA 判例：射程向 hullmod 必须摘除）。
        stripAllHullMods(player);
        player.getVariant().addWeapon("WS 012", ASTDInGameAutomationScenario.QJ_WEAPON_ID);
        player.getVariant().addWeapon("WS 013", ASTDInGameAutomationScenario.QJ_WEAPON_ID);

        final FleetMemberAPI enemy = api.addToFleet(FleetSide.ENEMY, "dominator_Assault", FleetMemberType.SHIP, false);
        clearAllWeaponSlots(enemy);
        stripAllHullMods(enemy);
        enemy.getVariant().addWeapon("WS 012", ASTDInGameAutomationScenario.QJ_WEAPON_ID);

        final FleetMemberAPI switchTarget = api.addToFleet(FleetSide.ENEMY, "vigilance_Standard", FleetMemberType.SHIP, false);
        clearAllWeaponSlots(switchTarget);

        final FleetMemberAPI killTarget = api.addToFleet(FleetSide.ENEMY, "vigilance_Standard", FleetMemberType.SHIP, false);
        clearAllWeaponSlots(killTarget);

        api.addBriefingItem("Deploy qiongjue phase railgun and observe stacking/switch/decay/scaling telemetry.");

        api.initMap(-9000f, 9000f, -6000f, 6000f);
        api.setBackgroundSpriteName("graphics/backgrounds/background2.jpg");
        api.addPlugin(new ASTDAutomationCombatPlugin());
    }

    private static void clearAllWeaponSlots(final FleetMemberAPI member) {
        for (final String slotId : member.getVariant().getFittedWeaponSlots()) {
            member.getVariant().clearSlot(slotId);
        }
    }

    /** 摘除全部外加 hullmod（舞台舰按基线 hull 断言；先复制集合防边迭代边删；Janino 不支持 diamond，显式类型参数）。 */
    private static void stripAllHullMods(final FleetMemberAPI member) {
        for (final String mod : new java.util.ArrayList<String>(member.getVariant().getHullMods())) {
            member.getVariant().removeMod(mod);
        }
    }
}
