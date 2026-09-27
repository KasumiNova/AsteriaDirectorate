package data.missions.lens_phase_flank_zw002;

import cn.kasuminova.astd.combat.effect.generic.ASTDAutomationCombatPlugin;
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario;
import com.fs.starfarer.api.fleet.FleetGoal;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.mission.FleetSide;
import com.fs.starfarer.api.mission.MissionDefinitionAPI;
import com.fs.starfarer.api.mission.MissionDefinitionPlugin;

/**
 * Dev-only mission surface for gravity phase cloak AI health check (ZW-002) in-game automation.
 * <p>
 * 矩阵扩展场景：密蒙级巡洋（astd_zw_002）对同一统治者级靶舰。遥测实证该对阵下
 * 密蒙呈风筝态势且辐能经济受限（相位速度 120 vs 布防距离 1400+，硬辐能闸 5s 内
 * 强制上浮），绕后穿透物理不可达——改走 HEALTH 判定（90s 观测窗内下潜 ≥2 且
 * 上浮 ≥2），验证高压风筝下的相位节奏健康。
 */
public final class MissionDefinition implements MissionDefinitionPlugin {
    @Override
    public void defineMission(final MissionDefinitionAPI api) {
        api.initFleet(FleetSide.PLAYER, "ASTD", FleetGoal.ATTACK, false, 5);
        api.initFleet(FleetSide.ENEMY, "DRONE", FleetGoal.ATTACK, true, 5);

        api.setFleetTagline(FleetSide.PLAYER, "ASTD automation: gravity phase flank AI (ZW-002)");
        api.setFleetTagline(FleetSide.ENEMY, "Automation flank target cruiser");

        api.addToFleet(FleetSide.PLAYER, ASTDInGameAutomationScenario.PF2_VARIANT_ID, FleetMemberType.SHIP, false);
        api.addToFleet(FleetSide.ENEMY, ASTDInGameAutomationScenario.PF_ENEMY_VARIANT_ID, FleetMemberType.SHIP, false);

        api.addBriefingItem("Deploy mi-meng and observe phase flank (attack-run / rear-arc surface) telemetry.");

        api.initMap(-9000f, 9000f, -6000f, 6000f);
        api.setBackgroundSpriteName("graphics/backgrounds/background2.jpg");
        api.addPlugin(new ASTDAutomationCombatPlugin());
    }
}
