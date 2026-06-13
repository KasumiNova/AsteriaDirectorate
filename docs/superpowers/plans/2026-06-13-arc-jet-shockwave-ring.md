# Arc Jet Shockwave Ring Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace every existing render effect owned by the `astd_arc_jet` ship system with the migrated `shockwave-ring` GLSL effect from `tools/game-vfx-preview`.

**Architecture:** Keep `ASTDArcSharedFluxNetworkSystemStats` gameplay behavior intact: source stat modifiers, target selection, target buffs, flux transfer, pressure smoothing, and target status remain. Move the new visual into `cn.kasuminova.astd.renderer.effect.system` as a combat layered renderer installed once per engine, and expose it through a narrow `ASTDArcProductionVfx.renderArcJetShockwaveRing(...)` wrapper for telemetry and automation. Remove the old Arc Jet system beam/star/path flare code paths from `ASTDArcProductionVfx`; Plasma Arch and Radiation Belt production VFX stay out of scope.

**Tech Stack:** Kotlin/JVM, Starsector combat API, LWJGL 2 OpenGL 2.0 GLSL, existing combat layered rendering plugin pattern, existing Python automation verifier, Kotlin test.

---

## Scope And Constraints

- The ship `astd_arc_jet` uses ship system `astd_arc_shared_flux_network`.
- The system script is `src/main/kotlin/cn/kasuminova/astd/combat/shipsystems/ASTDArcSharedFluxNetworkSystemStats.kt`.
- The current active system render call is per target: `ASTDArcProductionVfx.renderArcJetSharedFluxBeam(engine, ship, target, falloff * level, pressureRatio)`.
- Delete the old Arc Jet ship-system visual behavior:
  - multilayer continuous shared-flux beams,
  - BoxUtil flare fields on the beam path,
  - short traveling sub-beams,
  - source cross-star rays,
  - source star ring.
- Preserve non-Arc-Jet production VFX in `ASTDArcProductionVfx`: Plasma Arch shield arcs/colors/hit pulses and Radiation Belt temporal afterimages.
- The reference image size target is local to the ship, not the 1500su system range: `outerRadiusWorld = ship.collisionRadius * 1.5f`, which is about 405su for `astd_arc_jet`.
- The selected reference-image parameters are deliberately not the preview tool defaults:
  - `speed = 0.30`
  - `thickness = 0.01`
  - `ringCount = 3`
  - `distortion = 0.50`
  - `glow = 1.25`
  - `hue = 0.52`
  - `saturation = 0.60`
  - `exposure = 1.25`
- Do not add original particle or BoxUtil fallback branches for the Arc Jet shockwave. This shader effect needs a game-side GLSL renderer to preserve the preview effect.
- No reflection in implementation or tests.
- Add new tests as direct behavior tests, not pure source `contains` tests.

## File Structure

- Create: `src/main/kotlin/cn/kasuminova/astd/renderer/effect/system/ASTDArcJetShockwaveRingRenderer.kt`
  - Owns the shader source, renderer installation, per-ship active state, and direct spec/math helpers.
- Create: `src/test/kotlin/cn/kasuminova/astd/renderer/effect/system/ASTDArcJetShockwaveRingSpecTest.kt`
  - Directly tests radius, shader-domain mapping, effect-level alpha, pressure modulation, and stale-frame timing helpers.
- Modify: `src/main/kotlin/cn/kasuminova/astd/combat/hullmods/arc/ASTDArcProductionVfx.kt`
  - Remove old Arc Jet system render helpers.
  - Add `renderArcJetShockwaveRing(...)` wrapper and new telemetry keys.
  - Keep Plasma Arch and Radiation Belt methods.
- Modify: `src/main/kotlin/cn/kasuminova/astd/combat/shipsystems/ASTDArcSharedFluxNetworkSystemStats.kt`
  - Replace the per-target beam call with one per-source shockwave call per active frame.
  - Keep target loop logic and compute max pressure across targets for visual intensity.
- Modify: `src/main/kotlin/cn/kasuminova/astd/combat/effect/generic/ASTDAutomationCombatPlugin.kt`
  - Emit new Arc Jet shockwave telemetry fields.
- Modify: `contents/data/config/astd_automation_scenarios.json`
  - Replace obsolete Arc Jet beam evidence keys with shockwave evidence keys.
- Modify: `tools/verify_ingame_vfx_automation.py`
  - Require shockwave telemetry and relabel screenshot ROI from active flux network to shockwave ring.
- Modify: `tools/verify_ingame_vfx_automation_test.py`
  - Update synthetic telemetry keys and labels.
- Modify: `src/test/kotlin/cn/kasuminova/astd/internal/debug/ASTDInGameAutomationScenarioTest.kt`
  - Update required Arc Jet evidence keys.
- Modify: `src/test/kotlin/cn/kasuminova/astd/combat/hullmods/arc/ASTDArcProductionVfxTest.kt`
  - Remove obsolete Arc Jet beam/star assertions with the deleted feature.
  - Keep unrelated Plasma Arch / Radiation Belt assertions.
  - Do not add new Arc Jet source `contains` assertions; new Arc Jet coverage comes from direct spec tests and automation evidence.

### Task 1: Add Shockwave Spec Tests

**Files:**
- Create: `src/test/kotlin/cn/kasuminova/astd/renderer/effect/system/ASTDArcJetShockwaveRingSpecTest.kt`
- Create later: `src/main/kotlin/cn/kasuminova/astd/renderer/effect/system/ASTDArcJetShockwaveRingRenderer.kt`

- [ ] **Step 1: Write failing tests**

Test direct behavior:
- `outerRadiusWorld(270f) == 405f`
- `quadHalfExtentWorld == outerRadiusWorld`
- shader domain edge radius is `1.3f`, preserving preview `radius = phase * 1.3`
- reference parameters match the selected `shockwave-ring` reference values
- frame alpha clamps to `0f` at `effectLevel <= 0f` and increases with pressure at active level
- stale timeout is short enough to remove a ship that stops submitting frames after system end

- [ ] **Step 2: Run red test**

Run:

```bash
./gradlew test --tests cn.kasuminova.astd.renderer.effect.system.ASTDArcJetShockwaveRingSpecTest
```

Expected: compilation fails because `ASTDArcJetShockwaveRingRenderer` or its spec object does not exist.

### Task 2: Implement Game-Side Shockwave Renderer

**Files:**
- Create: `src/main/kotlin/cn/kasuminova/astd/renderer/effect/system/ASTDArcJetShockwaveRingRenderer.kt`
- Test: `src/test/kotlin/cn/kasuminova/astd/renderer/effect/system/ASTDArcJetShockwaveRingSpecTest.kt`

- [ ] **Step 1: Add the spec and renderer**

Implement:
- `ASTDArcJetShockwaveRingSpec`
  - constants listed in scope,
  - `outerRadiusWorld(collisionRadius: Float): Float`,
  - `frame(collisionRadius: Float, effectLevel: Float, pressureRatio: Float): Frame`,
  - `shouldRetire(ageSinceLastSubmit: Float): Boolean`.
- `ASTDArcJetShockwaveRingRenderer.render(engine, ship, effectLevel, pressureRatio)`
  - installs one `BaseCombatLayeredRenderingPlugin` in `engine.customData`,
  - updates one state per source ship identity,
  - renders on `CombatEngineLayers.BELOW_SHIPS_LAYER`,
  - draws a world-space square quad centered on the ship with half extent `outerRadiusWorld`,
  - uses a GLSL port of preview `shockwave-ring` with `hash21`, `valueNoise`, `fbm`, `hsv2rgb`, and `acesTonemap`,
  - scales centered UV by `1.3` so the preview ring formula reaches the requested world outer radius,
  - restores the previous GL program and GL state in `finally`.

Shader/link failures should throw `IllegalStateException` with the shader id and driver log. Do not silently ignore them or add a non-shader fallback.

- [ ] **Step 2: Run spec test green**

Run:

```bash
./gradlew test --tests cn.kasuminova.astd.renderer.effect.system.ASTDArcJetShockwaveRingSpecTest
```

Expected: PASS.

### Task 3: Replace Arc Jet System VFX Call Path

**Files:**
- Modify: `src/main/kotlin/cn/kasuminova/astd/combat/hullmods/arc/ASTDArcProductionVfx.kt`
- Modify: `src/main/kotlin/cn/kasuminova/astd/combat/shipsystems/ASTDArcSharedFluxNetworkSystemStats.kt`
- Modify: `src/test/kotlin/cn/kasuminova/astd/combat/hullmods/arc/ASTDArcProductionVfxTest.kt`

- [ ] **Step 1: Remove obsolete beam/star source assertions**

Delete the old Arc Jet source `contains` test block that requires:
- `renderArcJetSharedFluxBeam`,
- `renderFluxBeamLayer`,
- `emitFluxPathFlares`,
- `emitFluxTravelBeam`,
- `emitArcJetFluxStar`,
- source star segment/ring helpers.

Do not replace it with a new source `contains` test. The new shockwave behavior is covered by `ASTDArcJetShockwaveRingSpecTest`, automation evidence keys, the final symbol audit, and the optional in-game smoke test.

- [ ] **Step 2: Run the affected test red**

Run:

```bash
./gradlew test --tests cn.kasuminova.astd.combat.hullmods.arc.ASTDArcProductionVfxTest
```

Expected: FAIL is acceptable if other existing assertions still expect removed old Arc Jet symbols; otherwise continue with the direct spec red test from Task 1 as the primary TDD gate.

- [ ] **Step 3: Modify production code**

In `ASTDArcProductionVfx.kt`:
- import `ASTDArcJetShockwaveRingRenderer`,
- add telemetry keys:
  - `arcJetShockwaveFrames`,
  - `arcJetShockwaveRadius`,
  - `arcJetShockwaveFluxPressure`,
- add wrapper `renderArcJetShockwaveRing(engine, ship, level, pressureRatio)`,
- call renderer once,
- increment frame telemetry,
- write radius and pressure telemetry,
- delete obsolete Arc Jet beam/star/path flare functions and constants.

In `ASTDArcSharedFluxNetworkSystemStats.kt`:
- remove the old per-target render call,
- keep pressure smoothing per target,
- track the highest pressure ratio seen this frame,
- call `ASTDArcProductionVfx.renderArcJetShockwaveRing(engine, ship, level, maxPressureRatio)` once after target processing, including frames with no selected targets.

- [ ] **Step 4: Run affected test green**

Run:

```bash
./gradlew test --tests cn.kasuminova.astd.combat.hullmods.arc.ASTDArcProductionVfxTest
```

Expected: PASS.

### Task 4: Update Automation Evidence Contract

**Files:**
- Modify: `src/main/kotlin/cn/kasuminova/astd/combat/effect/generic/ASTDAutomationCombatPlugin.kt`
- Modify: `contents/data/config/astd_automation_scenarios.json`
- Modify: `tools/verify_ingame_vfx_automation.py`
- Modify: `tools/verify_ingame_vfx_automation_test.py`
- Modify: `src/test/kotlin/cn/kasuminova/astd/internal/debug/ASTDInGameAutomationScenarioTest.kt`

- [ ] **Step 1: Write/update failing evidence tests**

Replace required evidence keys:
- old: `arcJetActiveSystemLinks`
- old: `arcJetActiveSystemBeamFrames`
- old: `arcJetActiveSystemFluxPressure`
- new: `arcJetShockwaveFrames`
- new: `arcJetShockwaveRadius`
- new: `arcJetShockwaveFluxPressure`

Update the Arc Jet screenshot region label to `arc jet shockwave ring`.

- [ ] **Step 2: Run red tests**

Run:

```bash
./gradlew test --tests cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenarioTest
python3 tools/verify_ingame_vfx_automation_test.py
```

Expected: FAIL until production/config/verifier updates are complete.

- [ ] **Step 3: Update automation/config/verifier**

Synchronize all required evidence keys and telemetry JSON fields.

- [ ] **Step 4: Run evidence tests green**

Run:

```bash
./gradlew test --tests cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenarioTest
python3 tools/verify_ingame_vfx_automation_test.py
```

Expected: PASS.

### Task 5: Final Verification And Commit

**Files:**
- All files changed by Tasks 1-4.

- [ ] **Step 1: Run focused verification**

Run:

```bash
./gradlew test --tests cn.kasuminova.astd.renderer.effect.system.ASTDArcJetShockwaveRingSpecTest --tests cn.kasuminova.astd.combat.hullmods.arc.ASTDArcProductionVfxTest --tests cn.kasuminova.astd.internal.debug.ASTDInGameAutomationScenarioTest
python3 tools/verify_ingame_vfx_automation_test.py
git diff --check
rg -n "renderArcJetSharedFluxBeam|emitFluxPathFlares|emitFluxTravelBeam|emitArcJetFluxStar|renderFluxStarRing|renderFluxStarRay|fluxBeamAnchor|arcJetActiveSystemBeamFrames" src/main/kotlin src/test/kotlin contents/data/config tools/verify_ingame_vfx_automation.py tools/verify_ingame_vfx_automation_test.py
```

Expected: all commands except `rg` exit 0; `rg` must exit 1 with no matches.

- [ ] **Step 2: Run in-game automation if available**

Run:

```bash
./gradlew smokeTestGame
```

Expected: PASS with ARC production automation evidence. If this cannot run in the current environment, record the exact failure and do not claim in-game visual verification.

- [ ] **Step 3: Inspect diff**

Run:

```bash
git status --short
git diff --stat
git diff -- src/main/kotlin/cn/kasuminova/astd/combat/hullmods/arc/ASTDArcProductionVfx.kt src/main/kotlin/cn/kasuminova/astd/combat/shipsystems/ASTDArcSharedFluxNetworkSystemStats.kt src/main/kotlin/cn/kasuminova/astd/renderer/effect/system/ASTDArcJetShockwaveRingRenderer.kt
```

Confirm:
- no old Arc Jet beam/star/path flare render path remains,
- Plasma Arch and Radiation Belt VFX behavior remains,
- system gameplay logic remains,
- telemetry contract uses shockwave keys.

- [ ] **Step 4: Commit**

Run:

```bash
git add docs/superpowers/plans/2026-06-13-arc-jet-shockwave-ring.md \
  src/main/kotlin/cn/kasuminova/astd/renderer/effect/system/ASTDArcJetShockwaveRingRenderer.kt \
  src/test/kotlin/cn/kasuminova/astd/renderer/effect/system/ASTDArcJetShockwaveRingSpecTest.kt \
  src/main/kotlin/cn/kasuminova/astd/combat/hullmods/arc/ASTDArcProductionVfx.kt \
  src/main/kotlin/cn/kasuminova/astd/combat/shipsystems/ASTDArcSharedFluxNetworkSystemStats.kt \
  src/main/kotlin/cn/kasuminova/astd/combat/effect/generic/ASTDAutomationCombatPlugin.kt \
  contents/data/config/astd_automation_scenarios.json \
  tools/verify_ingame_vfx_automation.py \
  tools/verify_ingame_vfx_automation_test.py \
  src/test/kotlin/cn/kasuminova/astd/internal/debug/ASTDInGameAutomationScenarioTest.kt \
  src/test/kotlin/cn/kasuminova/astd/combat/hullmods/arc/ASTDArcProductionVfxTest.kt
git commit -m "feat(combat): replace arc jet vfx with shockwave ring"
```

Expected: one final implementation commit after the three prior cleanup commits.
