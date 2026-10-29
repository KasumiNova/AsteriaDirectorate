package data.missions.phase_ai_seed_2;

import cn.kasuminova.astd.combat.effect.generic.ASTDAutomationCombatPlugin;
import com.fs.starfarer.api.fleet.FleetGoal;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.mission.FleetSide;
import com.fs.starfarer.api.mission.MissionDefinitionAPI;
import com.fs.starfarer.api.mission.MissionDefinitionPlugin;

public final class MissionDefinition implements MissionDefinitionPlugin {
    @Override
    public void defineMission(final MissionDefinitionAPI api) {
        api.initFleet(FleetSide.PLAYER, "ASTD", FleetGoal.ATTACK, false, 16);
        api.initFleet(FleetSide.ENEMY, "DRONE", FleetGoal.ATTACK, true, 16);
        api.setFleetTagline(FleetSide.PLAYER, "ASTD automation: phase AI fixed-seed random combat #2");
        api.setFleetTagline(FleetSide.ENEMY, "Automation random enemy pool");
        cn.kasuminova.astd.combat.automation.base.AbstractPhaseAICombatScenario.addMissionFleet(api,
            new cn.kasuminova.astd.combat.automation.scenario.system.phaseai.PhaseAiSeed2Scenario());
        api.addBriefingItem("Fixed-seed random real combat. Scenario deploys ships per seed config.");
        api.initMap(-12000f, 12000f, -8000f, 8000f);
        api.setBackgroundSpriteName("graphics/backgrounds/background2.jpg");
        api.addPlugin(new ASTDAutomationCombatPlugin());
    }
}
