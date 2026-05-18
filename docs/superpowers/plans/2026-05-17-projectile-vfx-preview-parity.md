# Projectile VFX Preview Parity Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make in-game projectile VFX visually match the projectile VFX preview tool for AOD-7 and future presets, with deterministic screenshot validation.

**Architecture:** Treat the preview tool as the visual contract and move all shape/math/layer semantics into shared, testable specifications. In game, use BoxUtil entities first (`TrailEntity`, `SpriteEntity`, `SegmentEntity`, instanced `SpriteEntity`); add a custom high-performance combat rendering entity only for filled gradient mesh shapes that BoxUtil cannot represent faithfully. The preview renderer and game renderer must consume the same layer model and deterministic capture timeline.

**Tech Stack:** Kotlin/JVM 17, Starsector 0.98 combat API, BoxUtil render entities, OpenGL custom combat renderer only where needed, TypeScript/Vite/Vitest preview tool, Python/OpenCV screenshot verifier.

---

## Non-Negotiable Acceptance Criteria

- Preview and in-game AOD-7 capture use the same preset id: `aod7_shot`.
- At the accepted frame, both views show the same visual composition: left-fading trail, bright head at the right, filled spear/beam body, glow bands, mist, side wisps, and ribbon.
- Game renderer must not leave the head as only a `SegmentEntity` outline when the preview shows a filled head.
- Game renderer must not show a native Starsector projectile body competing with ASTD VFX.
- Pixel validation must compare a preview reference capture against a game screenshot, not only check that "some bright pixels exist".
- BoxUtil is the first choice for all effects it can express efficiently. Custom renderer is allowed only for filled gradient polygons / mesh shapes that require Canvas-like fill semantics.

## Current Root Cause Summary

- `tools/projectile-vfx-preview/src/render/previewOverlayRenderer.ts` draws extra visual layers that do not exist in the game runtime:
  - `drawBeamShape()` fills a spear/beam polygon with gradient and shadow.
  - `drawProjectileHead()` fills a closed curved head with gradient, blur, and shadow.
- `src/main/kotlin/cn/kasuminova/astd/renderer/projectile/runtime/ASTDProjectileVfxHeadRenderer.kt` currently uses `SegmentEntity`, producing an outline/叉形 rather than a filled head.
- `src/main/kotlin/cn/kasuminova/astd/renderer/projectile/runtime/ASTDProjectileVfxTrailRenderer.kt` draws a two-node trail and cannot reproduce the preview's filled body by itself.
- `contents/data/weapons/proj/astd_aod7_shot.proj` still has native projectile dimensions and colors, which can visually overlap the custom VFX.
- `ASTDAutomationCombatPlugin.pinFallbackProjectileForEvidence()` freezes fallback projectile velocity; this is useful for evidence, but does not reproduce the preview's moving history unless the runtime gets a deterministic capture mode.

## File Boundary Map

- Modify `tools/projectile-vfx-preview/src/render/previewOverlayRenderer.ts` only to route visual primitives through named contract helpers; avoid adding more preview-only math.
- Modify `tools/projectile-vfx-preview/src/render/projectileVfxLayout.ts` to expose beam body/head/glow/mist geometry and alpha contracts.
- Modify `tools/projectile-vfx-preview/src/render/projectileVfxLayout.test.ts` and `previewOverlayRenderer.test.ts` to pin AOD-7 reference geometry.
- Modify `src/main/kotlin/cn/kasuminova/astd/renderer/projectile/runtime/ASTDProjectileVfxLayout.kt` to mirror every preview contract helper.
- Create `src/main/kotlin/cn/kasuminova/astd/renderer/projectile/runtime/ASTDProjectileVfxBodyRenderer.kt` for the filled spear/beam body.
- Replace or extend `src/main/kotlin/cn/kasuminova/astd/renderer/projectile/runtime/ASTDProjectileVfxHeadRenderer.kt` so filled head rendering is the primary path.
- Modify `src/main/kotlin/cn/kasuminova/astd/renderer/projectile/runtime/ASTDProjectileVfxRenderGraph.kt` to include body/head filled layers in the same order as the preview.
- Modify `src/main/kotlin/cn/kasuminova/astd/renderer/projectile/ASTDProjectileVfxPreset.kt` only if the visual contract needs explicit body layer config.
- Modify `src/main/kotlin/cn/kasuminova/astd/renderer/projectile/ASTDProjectileVfxPresetCatalog.kt` only to add explicit `aod7_shot` body/head parameters that already exist implicitly in preview code.
- Create tests:
  - `src/test/kotlin/cn/kasuminova/astd/renderer/projectile/ASTDProjectileVfxBodyRendererTest.kt`
  - extend `ASTDProjectileVfxHeadRendererTest.kt`
  - extend `ASTDProjectileVfxLayoutParityTest.kt`
  - extend `ASTDProjectileVfxRenderGraphTest.kt`
- Modify `contents/data/weapons/proj/astd_aod7_shot.proj` only if verified native projectile pixels remain visible; keep collision behavior intact.
- Modify `src/main/kotlin/cn/kasuminova/astd/combat/effect/generic/ASTDAutomationCombatPlugin.kt` to support deterministic parity capture mode, not ad hoc staging.
- Modify `tools/verify_ingame_vfx_automation.py` to compare preview reference and game screenshot structure.
- Create `tools/projectile-vfx-preview/src/ui/parityCapture.ts` or equivalent CLI-friendly capture hook if current capture code cannot export deterministic frames.
- Store reference evidence under `docs/dev-docs/projectile-vfx-parity/captures/`.

## Renderer Strategy

- Trail: keep BoxUtil `TrailEntity`.
- Glow: keep BoxUtil `TrailEntity` layers.
- Mist: keep BoxUtil `SpriteEntity` with instance data.
- Side wisps: keep BoxUtil `TrailEntity`.
- Ribbon: keep BoxUtil `TrailEntity`, but feed deterministic moving history in automation.
- Filled body: first attempt BoxUtil using layered `TrailEntity` only if it can reproduce the preview polygon. If not, implement one custom high-performance combat render entity that batches filled additive triangle strips.
- Filled head: first attempt BoxUtil `SpriteEntity` with generated white wedge/head mask if it can match gradient via material/emissive tint. If not, use the same custom mesh entity as body.
- Outline `SegmentEntity`: keep only as optional debug overlay or remove from normal AOD-7 render graph.

## Task 1: Lock The Visual Contract In Preview Tests

**Files:**
- Modify: `tools/projectile-vfx-preview/src/render/projectileVfxLayout.ts`
- Modify: `tools/projectile-vfx-preview/src/render/projectileVfxLayout.test.ts`
- Modify: `tools/projectile-vfx-preview/src/render/previewOverlayRenderer.ts`
- Modify: `tools/projectile-vfx-preview/src/render/previewOverlayRenderer.test.ts`

- [ ] **Step 1: Add body/head contract helper tests**

Add tests for AOD-7 at `pulse=1`, `widthBase=6`, `visibleLength=420`:
- body polygon has tip at `(0, 0)`.
- body tail extends to negative X.
- body width is centered around Y=0.
- head vertices match existing `projectileVfxHeadVertices()` output.
- head alpha is `pulse * headVisible * alphaScale`.

- [ ] **Step 2: Run preview tests and confirm they fail before helper extraction**

Run:

```bash
cd tools/projectile-vfx-preview
npm run test:run -- src/render/projectileVfxLayout.test.ts src/render/previewOverlayRenderer.test.ts
```

Expected: FAIL until explicit body/head contract helpers exist.

- [ ] **Step 3: Extract preview-only hardcoded geometry into exported helpers**

Move the implicit math from `drawBeamShape()` and `drawProjectileHead()` into named functions in `projectileVfxLayout.ts`, for example:

```ts
export function projectileVfxBodyPolygon(widthBase: number, visibleLength: number, pulse: number): Vec2[] { ... }
export function projectileVfxBodyGradientStops(...): ProjectileVfxGradientStop[] { ... }
export function projectileVfxHeadFillLayout(...): ProjectileVfxHeadFillLayout { ... }
```

Do not change rendered output in this task.

- [ ] **Step 4: Route `previewOverlayRenderer.ts` through helpers**

`drawBeamShape()` and `drawProjectileHead()` should call the exported helpers. Keep Canvas drawing behavior identical.

- [ ] **Step 5: Verify preview tests pass**

Run:

```bash
cd tools/projectile-vfx-preview
npm run test:run -- src/render/projectileVfxLayout.test.ts src/render/previewOverlayRenderer.test.ts
```

Expected: PASS.

## Task 2: Mirror The Contract In Kotlin

**Files:**
- Modify: `src/main/kotlin/cn/kasuminova/astd/renderer/projectile/runtime/ASTDProjectileVfxLayout.kt`
- Modify: `src/test/kotlin/cn/kasuminova/astd/renderer/projectile/ASTDProjectileVfxLayoutParityTest.kt`

- [ ] **Step 1: Write failing Kotlin parity tests**

Add parity assertions matching the TypeScript constants:
- `bodyPolygon(widthBase=6, visibleLength=420, pulse=1)` produces the same vertices as preview.
- `headFillLayout()` produces the same alpha and dimensions as preview.
- body and head shrink with the same `smoothstep` thresholds used in preview.

- [ ] **Step 2: Run Kotlin parity test and verify failure**

Run:

```bash
./gradlew test --tests '*ASTDProjectileVfxLayoutParityTest' --rerun-tasks
```

Expected: FAIL because Kotlin does not expose body/head fill contract yet.

- [ ] **Step 3: Implement Kotlin contract helpers**

Add `BodyPolygon`, `BodyGradientStop`, and `HeadFillLayout` data classes/functions to `ASTDProjectileVfxLayout.kt`. Use the same numeric thresholds as preview.

- [ ] **Step 4: Verify Kotlin parity passes**

Run:

```bash
./gradlew test --tests '*ASTDProjectileVfxLayoutParityTest' --rerun-tasks
```

Expected: PASS.

## Task 3: Add Filled Body Runtime Layer

**Files:**
- Create: `src/main/kotlin/cn/kasuminova/astd/renderer/projectile/runtime/ASTDProjectileVfxBodyRenderer.kt`
- Modify: `src/main/kotlin/cn/kasuminova/astd/renderer/projectile/runtime/ASTDProjectileVfxRenderGraph.kt`
- Create: `src/test/kotlin/cn/kasuminova/astd/renderer/projectile/ASTDProjectileVfxBodyRendererTest.kt`
- Modify: `src/test/kotlin/cn/kasuminova/astd/renderer/projectile/ASTDProjectileVfxRenderGraphTest.kt`

- [ ] **Step 1: Write failing renderer tests**

Test that `aod7_shot` render graph includes a body layer before head and after glow/trail according to the preview order:

```text
mist -> glow -> body -> trail/decorations -> sideWisps -> head -> ribbon
```

Use the actual order that best matches blending in Starsector after testing; document any required order deviation.

- [ ] **Step 2: Decide BoxUtil feasibility with a small prototype**

Check if `TrailEntity` or `SpriteEntity` can reproduce the body polygon:
- If `TrailEntity` cannot fill the spear-shaped shoulder/tip polygon, reject it for body.
- If `SpriteEntity` requires a generated mask and cannot represent the length-dependent polygon without too many unique textures, reject it for body.

Document the decision in `docs/dev-docs/projectile-vfx-parity/2026-05-17-runtime-renderer-decision.md`.

- [ ] **Step 3: Implement body with preferred renderer**

Preferred order:
1. BoxUtil entity if visually faithful.
2. Custom `BaseCombatLayeredRenderingPlugin` or BoxUtil-compatible custom render data entity that batches additive triangles.

The implementation must:
- consume `ASTDProjectileVfxLayout.bodyPolygon()`;
- update vertices every frame from `context.visibleLength` and `context.beamAlpha`;
- use additive blending;
- bind no per-frame allocated textures;
- delete/cleanup consistently with other runtime layers.

- [ ] **Step 4: Verify unit tests**

Run:

```bash
./gradlew test --tests '*ASTDProjectileVfxBodyRendererTest' --tests '*ASTDProjectileVfxRenderGraphTest' --rerun-tasks
```

Expected: PASS.

## Task 4: Replace Outline Head With Filled Head

**Files:**
- Modify: `src/main/kotlin/cn/kasuminova/astd/renderer/projectile/runtime/ASTDProjectileVfxHeadRenderer.kt`
- Modify: `src/test/kotlin/cn/kasuminova/astd/renderer/projectile/ASTDProjectileVfxHeadRendererTest.kt`

- [ ] **Step 1: Write failing head renderer tests**

Tests must prove normal AOD-7 head rendering uses filled geometry or mask-based filled rendering, not only `SegmentEntity` edge pairs.

- [ ] **Step 2: Select BoxUtil-first head implementation**

Try this order:
1. `SpriteEntity` with generated wedge/head alpha mask and emissive gradient approximation.
2. Custom mesh renderer using the same polygon vertices as preview.

Reject `SegmentEntity` as the main renderer because it cannot fill the head.

- [ ] **Step 3: Implement filled head**

Implementation requirements:
- preserve `projectileVfxHeadVertices()` / `ASTDProjectileVfxLayout.headVertices()` coordinates;
- use preview's `headVisible = smoothstep(0.2, 0.72, pulse)`;
- match `alpha = pulse * headVisible * alphaScale`;
- keep optional segment outline only behind a debug flag, disabled by default.

- [ ] **Step 4: Verify tests**

Run:

```bash
./gradlew test --tests '*ASTDProjectileVfxHeadRendererTest' --rerun-tasks
```

Expected: PASS.

## Task 5: Remove Native Projectile Visual Interference

**Files:**
- Modify if needed: `contents/data/weapons/proj/astd_aod7_shot.proj`
- Add or modify test if project has data validation coverage.

- [ ] **Step 1: Capture with all ASTD VFX layers disabled**

Temporarily set `contents/data/config/astd_projectile_vfx_debug.json` layer flags false for local evidence only, or add a temporary debug scenario. Verify whether the native projectile still draws visible pixels.

- [ ] **Step 2: If native projectile is visible, neutralize visual fields**

Candidate `.proj` changes:
- keep collision/damage behavior intact;
- reduce visual `length`/`width` to minimum safe values;
- set `fringeColor` and `coreColor` alpha to `0` if Starsector accepts it;
- keep `bulletSprite` invisible.

- [ ] **Step 3: Re-enable ASTD VFX and verify no native yellow/orange line remains**

Use screenshot crop comparison and log notes.

## Task 6: Deterministic Preview Reference Capture

**Files:**
- Modify: `tools/projectile-vfx-preview/src/ui/capture.ts`
- Create if needed: `tools/projectile-vfx-preview/src/ui/parityCapture.ts`
- Modify: `tools/projectile-vfx-preview/src/ui/capture.test.ts`
- Modify: `tools/projectile-vfx-preview/README.md`

- [ ] **Step 1: Add deterministic capture API**

Expose a way to render preset `aod7_shot` at a fixed:
- canvas size,
- elapsed time,
- layer visibility set,
- background mode suitable for comparison.

- [ ] **Step 2: Add tests for capture metadata**

Expected metadata:
- preset id,
- elapsed seconds,
- size,
- enabled layers,
- generated image path.

- [ ] **Step 3: Generate reference capture**

Run:

```bash
cd tools/projectile-vfx-preview
npm run test:run
npm run build
```

Then run the capture command or documented manual capture path and save:

```text
docs/dev-docs/projectile-vfx-parity/captures/preview/aod7-all-layers-reference.png
```

## Task 7: Deterministic Game Capture Mode

**Files:**
- Modify: `src/main/kotlin/cn/kasuminova/astd/combat/effect/generic/ASTDAutomationCombatPlugin.kt`
- Modify: `src/main/kotlin/cn/kasuminova/astd/internal/debug/ASTDInGameAutomationScenario.kt`
- Modify: `contents/data/config/astd_automation_scenarios.json`
- Modify: `src/test/kotlin/cn/kasuminova/astd/internal/debug/ASTDInGameAutomationScenarioTest.kt`

- [ ] **Step 1: Write failing scenario tests**

Test config supports:
- parity elapsed time;
- deterministic projectile movement or seeded history;
- layer visibility;
- expected screenshot file naming.

- [ ] **Step 2: Stop using a frozen projectile for parity capture**

For parity mode, choose one:
- move the fallback projectile deterministically along the same timeline as preview; or
- seed runtime history nodes to the same path used by preview.

Do not rely on `velocity=0` for parity screenshots.

- [ ] **Step 3: Emit complete capture metadata**

Telemetry must include:
- screenshot dimensions;
- viewport visible size;
- capture elapsed;
- projectile anchor;
- render preset id;
- layer visibility;
- preview reference path used for comparison.

- [ ] **Step 4: Verify targeted automation tests**

Run:

```bash
./gradlew test --tests '*ASTDInGameAutomationScenarioTest' --rerun-tasks
```

Expected: PASS.

## Task 8: Pixel/Structure Parity Verifier

**Files:**
- Modify: `tools/verify_ingame_vfx_automation.py`

- [ ] **Step 1: Add reference-image argument**

Add:

```bash
--preview-reference docs/dev-docs/projectile-vfx-parity/captures/preview/aod7-all-layers-reference.png
```

- [ ] **Step 2: Compare normalized projectile ROI**

The verifier should:
- crop the projectile/VFX ROI from both images;
- normalize scale and position using telemetry anchors;
- compare structural similarity or feature masks;
- separately check head, body, trail, glow, and ship flattening.

- [ ] **Step 3: Keep existing hard checks**

Preserve:
- screenshot resolution check;
- ship template non-flattening check;
- telemetry scenario/id checks.

- [ ] **Step 4: Add failure messages that name missing layer**

Example failures:
- `head filled area missing`;
- `body polygon too thin`;
- `native projectile line detected`;
- `trail length mismatch`;
- `glow envelope mismatch`.

## Task 9: Full Verification Pass

**Files:**
- No direct code changes unless verification exposes a bug.

- [ ] **Step 1: Run preview tests**

```bash
cd tools/projectile-vfx-preview
npm run test:run
npm run build
```

Expected: all tests pass and preview builds.

- [ ] **Step 2: Run Kotlin tests**

```bash
./gradlew test --tests 'cn.kasuminova.astd.renderer.projectile.*' --rerun-tasks
./gradlew test --tests '*ASTDInGameAutomationScenarioTest' --rerun-tasks
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Deploy mod**

```bash
./gradlew deployMod
```

Expected: deployment succeeds.

- [ ] **Step 4: Clean previous automation output**

```bash
find /mnt/windows_data/Games/Starsector098-linux/ssoptimizer-automation-output -name 'astd-ingame-automation-*' -delete
```

- [ ] **Step 5: Run game automation**

```bash
bash tools/smoke_test_game_launch.sh /mnt/windows_data/Games/Starsector098-linux 45 automation
```

Expected: automation completes and writes screenshot/telemetry.

- [ ] **Step 6: Run parity verifier**

```bash
python3 tools/verify_ingame_vfx_automation.py \
  /mnt/windows_data/Games/Starsector098-linux/ssoptimizer-automation-output/astd-ingame-automation-telemetry.json \
  --require-screenshot-file \
  --preview-reference docs/dev-docs/projectile-vfx-parity/captures/preview/aod7-all-layers-reference.png
```

Expected: PASS with per-layer parity details.

## Task 10: Documentation And Maintenance Guardrails

**Files:**
- Create: `docs/dev-docs/projectile-vfx-parity/2026-05-17-runtime-renderer-decision.md`
- Modify: `docs/dev-docs/projectile-vfx-parity/2026-05-12-aod7-correction-report.md` or create a new completion report.
- Modify: `tools/projectile-vfx-preview/README.md`

- [ ] **Step 1: Document renderer decisions**

Record each layer:
- renderer type;
- why BoxUtil was sufficient or insufficient;
- exact tests covering it.

- [ ] **Step 2: Document parity capture workflow**

Include exact commands and expected output paths.

- [ ] **Step 3: Document future preset rule**

Any future preview-only visual primitive must either:
- have a Kotlin runtime implementation in the same change; or
- be marked preview-only and excluded from parity expectations.

## Final Definition Of Done

- `npm run test:run` and `npm run build` pass in `tools/projectile-vfx-preview`.
- Kotlin projectile runtime tests pass.
- In-game automation passes.
- `tools/verify_ingame_vfx_automation.py` compares preview reference to game screenshot and passes.
- The final screenshot visibly contains the Arc Flare hull, a non-flattened viewport, and an AOD-7 projectile matching preview composition.
- No normal-path AOD-7 head is rendered only as a line outline.
- No native projectile line visually dominates the ASTD projectile VFX.
