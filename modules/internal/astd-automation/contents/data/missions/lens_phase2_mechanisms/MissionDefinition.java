package data.missions.lens_phase2_mechanisms;

import cn.kasuminova.astd.combat.effect.generic.ASTDAutomationCombatPlugin;
import com.fs.starfarer.api.fleet.FleetGoal;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.mission.FleetSide;
import com.fs.starfarer.api.mission.MissionDefinitionAPI;
import com.fs.starfarer.api.mission.MissionDefinitionPlugin;

/**
 * Dev-only mission surface for phase-2 gravitational lens automation evidence.
 *
 * <p>Deploys two gravitational lens flagships and an enemy cluster so the phase-2 combat plugin
 * can exercise the full lens mechanism + shader pipeline:</p>
 * <ul>
 *   <li><b>Crewed lens</b> ({@code astd_gravitational_lens_Standard}, default dual-mode = crewed):
 *       drives the echo-fixation field spawn (script-driven), the permeating-tide deep-water
 *       stacking on the enemy cluster (auto, enemies sit within the 2500su tide field), the
 *       drift / deep-water mark shader highlights, and carries the parallax-decks + permeating-tide
 *       built-in hullmods (fighter-driven drift is degraded to a hullmod-mount assertion, see plugin).</li>
 *   <li><b>Unmanned lens</b> ({@code astd_gravitational_lens_Automated}, permaMod = automated mode):
 *       the only mode that runs ghost-signal — the plugin spawns enemy missiles inside its 2000su
 *       ghost range so the real defuse path emits ghost-signal waves.</li>
 *   <li><b>Enemy cluster</b>: multiple ships held near the lenses to receive deep-water stacks and
 *       cognitive tear on echo-field replay.</li>
 * </ul>
 *
 * <p>Ships are positioned by the combat plugin ({@code advanceLensPhase2Scenario}); the anchors here
 * are only initial deploy hints.</p>
 */
public final class MissionDefinition implements MissionDefinitionPlugin {
    @Override
    public void defineMission(final MissionDefinitionAPI api) {
        api.initFleet(FleetSide.PLAYER, "ASTD", FleetGoal.ATTACK, false, 5);
        api.initFleet(FleetSide.ENEMY, "DRONE", FleetGoal.ATTACK, true, 5);

        api.setFleetTagline(FleetSide.PLAYER, "ASTD automation: gravitational lens phase-2");
        api.setFleetTagline(FleetSide.ENEMY, "Automation target fleet");

        // Crewed flagship (echo field / tide / marks / parallax+tide hullmods).
        api.addToFleet(FleetSide.PLAYER, "astd_gravitational_lens_Standard", FleetMemberType.SHIP, true);
        // Unmanned lens (ghost-signal: unmanned-only feature).
        api.addToFleet(FleetSide.PLAYER, "astd_gravitational_lens_Automated", FleetMemberType.SHIP, false);

        // Enemy cluster: deep-water stacking + echo cognitive-tear targets.
        api.addToFleet(FleetSide.ENEMY, "enforcer_Assault", FleetMemberType.SHIP, false);
        api.addToFleet(FleetSide.ENEMY, "enforcer_Assault", FleetMemberType.SHIP, false);
        api.addToFleet(FleetSide.ENEMY, "lasher_Standard", FleetMemberType.SHIP, false);
        api.addToFleet(FleetSide.ENEMY, "lasher_Standard", FleetMemberType.SHIP, false);

        api.defeatOnShipLoss("ASTD astd_gravitational_lens");
        api.addBriefingItem("Deploy the gravitational lenses and observe phase-2 mechanism + shader telemetry.");

        api.initMap(-9000f, 9000f, -6000f, 6000f);
        api.setBackgroundSpriteName("graphics/backgrounds/background2.jpg");
        api.addPlugin(new ASTDAutomationCombatPlugin());
    }
}
