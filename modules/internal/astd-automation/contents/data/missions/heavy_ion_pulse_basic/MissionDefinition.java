package data.missions.heavy_ion_pulse_basic;

import cn.kasuminova.astd.combat.effect.generic.ASTDAutomationCombatPlugin;
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario;
import com.fs.starfarer.api.fleet.FleetGoal;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.mission.FleetSide;
import com.fs.starfarer.api.mission.MissionDefinitionAPI;
import com.fs.starfarer.api.mission.MissionDefinitionPlugin;

/**
 * Dev-only mission surface for heavy ion pulse in-game automation.
 *
 * 玩家桑德级与敌方桑德级（均清空全部槽位后大型能量槽 WS 003 装重型离子脉冲，全走 reserves
 * 由插件手动 spawn，玩家舰身份由插件 setPlayerShipExternal 赋予）。
 * 相位机验证：装配（双炮管/ammo 40/射程 700）→ 护盾命中无电弧 → 船体泄放电弧 + 弹匣节奏 →
 * k_s=5 玩家恒 v2（无贯穿）→ 敌版 k_s=2 无贯穿 → 敌版 k_s=5 EMP 贯穿浮字（§2.5 待验证项核对）。
 */
public final class MissionDefinition implements MissionDefinitionPlugin {
    @Override
    public void defineMission(final MissionDefinitionAPI api) {
        api.initFleet(FleetSide.PLAYER, "ASTD", FleetGoal.ATTACK, false, 5);
        api.initFleet(FleetSide.ENEMY, "DRONE", FleetGoal.ATTACK, true, 5);

        api.setFleetTagline(FleetSide.PLAYER, "ASTD automation: heavy ion pulse discharge/emp-pierce");
        api.setFleetTagline(FleetSide.ENEMY, "Automation target/carrier fleet");

        final FleetMemberAPI player = api.addToFleet(FleetSide.PLAYER, "sunder_Assault", FleetMemberType.SHIP, false);
        clearAllWeaponSlots(player);
        player.getVariant().addWeapon("WS 003", ASTDInGameAutomationScenario.HIP_WEAPON_ID);
        // §2.5 待验证项观测面：小型 PD（低 EMP 阈值，电弧易瘫痪）——玩家舰在 PIERCE_K5 相位
        // 作为 mult≈0 目标时，若贯穿追加 EMP 穿透抗性减免，小型槽应先于大槽出现瘫痪。
        player.getVariant().addWeapon("WS 006", "vulcan");

        final FleetMemberAPI enemy = api.addToFleet(FleetSide.ENEMY, "sunder_Assault", FleetMemberType.SHIP, false);
        clearAllWeaponSlots(enemy);
        enemy.getVariant().addWeapon("WS 003", ASTDInGameAutomationScenario.HIP_WEAPON_ID);
        // HULL 相位 mult=1.0 正向对照同款观测面（证明舞台 EMP 瘫痪机制生效）。
        enemy.getVariant().addWeapon("WS 006", "vulcan");

        api.addBriefingItem("Deploy heavy ion pulses and observe discharge/emp-pierce telemetry.");

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
