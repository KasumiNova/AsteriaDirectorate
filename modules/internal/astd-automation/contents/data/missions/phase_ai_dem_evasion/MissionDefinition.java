package data.missions.phase_ai_dem_evasion;

import cn.kasuminova.astd.combat.effect.generic.ASTDAutomationCombatPlugin;
import com.fs.starfarer.api.fleet.FleetGoal;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.mission.FleetSide;
import com.fs.starfarer.api.mission.MissionDefinitionAPI;
import com.fs.starfarer.api.mission.MissionDefinitionPlugin;

public final class MissionDefinition implements MissionDefinitionPlugin {
    @Override
    public void defineMission(final MissionDefinitionAPI api) {
        api.initFleet(FleetSide.PLAYER, "ASTD", FleetGoal.ATTACK, false, 5);
        api.initFleet(FleetSide.ENEMY, "DRONE", FleetGoal.ATTACK, true, 5);
        api.setFleetTagline(FleetSide.PLAYER, "ASTD automation: phase AI vs conquest DEM");
        api.setFleetTagline(FleetSide.ENEMY, "Automation conquest DEM variant");
        cn.kasuminova.astd.combat.automation.base.AbstractPhaseAICombatScenario.addMissionFleet(api,
            new cn.kasuminova.astd.combat.automation.scenario.system.phaseai.PhaseAiDemEvasionScenario());
        api.addBriefingItem("Observe enemy conquest DEM use Dragonfire/Hydra missiles; phase AI must evade and re-engage.");
        api.initMap(-9000f, 9000f, -6000f, 6000f);
        api.setBackgroundSpriteName("graphics/backgrounds/background2.jpg");
        api.addPlugin(new ASTDAutomationCombatPlugin());
    }
}
