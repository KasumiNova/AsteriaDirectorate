package data.missions.lens_phase_friendly_beam;

import cn.kasuminova.astd.combat.effect.generic.ASTDAutomationCombatPlugin;
import cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenario;
import com.fs.starfarer.api.fleet.FleetGoal;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.mission.FleetSide;
import com.fs.starfarer.api.mission.MissionDefinitionAPI;
import com.fs.starfarer.api.mission.MissionDefinitionPlugin;

/**
 * Dev-only mission surface for gravity phase cloak friendly-fire defensive dive in-game automation.
 * <p>
 * 舜华孤立待命（敌靶舰远锚 7000su，观测窗内不会接战），插件周期性生成友方
 * （owner 与本舰同侧）hellbore 直射弹直指本舰——验证 GravityPhaseCloakAI 的
 * collectFriendly 防御链路（友军火力并入 defensiveSoon 触发紧急下潜）实机生效：
 * 投喂后 1.5s 内出现下潜沿记一次命中，60s 观测窗内命中 ≥2 次判 Completed。
 */
public final class MissionDefinition implements MissionDefinitionPlugin {
    @Override
    public void defineMission(final MissionDefinitionAPI api) {
        api.initFleet(FleetSide.PLAYER, "ASTD", FleetGoal.ATTACK, false, 5);
        api.initFleet(FleetSide.ENEMY, "DRONE", FleetGoal.ATTACK, true, 5);

        api.setFleetTagline(FleetSide.PLAYER, "ASTD automation: phase friendly-fire defensive dive");
        api.setFleetTagline(FleetSide.ENEMY, "Distant dummy target cruiser");

        api.addToFleet(FleetSide.PLAYER, ASTDInGameAutomationScenario.PFB_VARIANT_ID, FleetMemberType.SHIP, false);
        api.addToFleet(FleetSide.ENEMY, ASTDInGameAutomationScenario.PFB_ENEMY_VARIANT_ID, FleetMemberType.SHIP, false);

        api.addBriefingItem("Deploy shun-hua isolated and observe defensive dives against fed friendly shots.");

        api.initMap(-9000f, 9000f, -6000f, 6000f);
        api.setBackgroundSpriteName("graphics/backgrounds/background2.jpg");
        api.addPlugin(new ASTDAutomationCombatPlugin());
    }
}
