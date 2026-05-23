# Projectile VFX Component Runtime And Beam Editor Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild projectile VFX as a Kotlin-first, hot-reloadable, data-plus-component runtime, then add a first-pass TS beam VFX editor for straight and curved BoxUtil-style beams.

**Architecture:** Round 1 replaces the JSON import path with Kotlin component presets and a component registry that builds render layers from component specs. Round 2 adds an independent beam editor prototype that models layered straight/curved beams in TS, exports Kotlin component parameters, and targets BoxUtil CurveEntity/beam-style runtime implementation later.

**Tech Stack:** Kotlin/JVM 17, Starsector combat runtime, BoxUtil TrailEntity/CurveEntity/SegmentEntity, Vite React/TypeScript, Vitest, Gradle tests.

---

## Scope And Non-Negotiable Constraints

- Kotlin is the runtime source of truth for projectile effects.
- JSON game preset import is removed, not kept as a fallback path.
- Existing JSON values are migrated into Kotlin component parameter objects.
- Renderers are created through component specs, not hardcoded top-level preset fields.
- Existing `trail`, `ribbon`, `head`, `glow`, `mist`, and `sideWisp` behavior becomes preset component types.
- Runtime errors should fail loudly with actionable messages; avoid broad fallback rendering paths.
- BoxUtil remains the preferred and expected rendering backend.
- TS projectile editor may still exist as an authoring/debug tool, but game runtime export must target Kotlin component code.
- Beam editor first pass does not need exact in-game parity, but must be functionally useful and visually representative.

## Current State Summary

- Runtime preset shape is `ASTDProjectileVfxPreset` with top-level lists such as `trailEntities`, `headLayers`, `glowLayers`, `mistLayers`, `sideWispLayers`, and `ribbonDecorations`.
- JSON import currently lives in `ASTDProjectileVfxPresetJson.kt` and `ASTDProjectileVfxPresetCatalog.gameExportPresetOrFallback`.
- Render graph assembly is hardcoded in `ASTDProjectileVfxRenderGraph.layersFor`.
- Most component renderers already exist as `ASTDProjectileVfxRenderLayer` implementations, so this plan focuses on ownership boundaries and data flow before visual rewrites.
- TS projectile editor currently exports JSON and Kotlin text, and the CLI writes `contents/data/config/astd_projectile_vfx_presets/aod7_shot.json`.

## Round 1 File Structure

- Modify `src/main/kotlin/cn/kasuminova/astd/renderer/projectile/ASTDProjectileVfxPreset.kt`
  - Replace top-level renderer lists with component specs.
  - Keep shared lifecycle/sampling/fade data.
- Create `src/main/kotlin/cn/kasuminova/astd/renderer/projectile/component/ASTDProjectileVfxComponentSpec.kt`
  - Sealed component spec hierarchy: `Trail`, `Ribbon`, `Head`, `Glow`, `Mist`, `SideWisp`, `Extra`.
- Create `src/main/kotlin/cn/kasuminova/astd/renderer/projectile/component/ASTDProjectileVfxComponentRegistry.kt`
  - Maps component spec type to render-layer factory.
- Create `src/main/kotlin/cn/kasuminova/astd/renderer/projectile/component/ASTDProjectileVfxComponentContext.kt`
  - Holds shared references resolved from component ids, especially trail anchors.
- Modify `src/main/kotlin/cn/kasuminova/astd/renderer/projectile/runtime/ASTDProjectileVfxRenderGraph.kt`
  - Build layers by iterating component specs through registry.
- Modify existing renderer files under `src/main/kotlin/cn/kasuminova/astd/renderer/projectile/runtime/`
  - Accept component specs instead of legacy top-level preset slices where needed.
- Modify `src/main/kotlin/cn/kasuminova/astd/renderer/projectile/ASTDProjectileVfxPresetCatalog.kt`
  - Define `aod7_shot` and built-in presets in Kotlin component form.
  - Add hot-reload source lookup.
- Delete `src/main/kotlin/cn/kasuminova/astd/renderer/projectile/ASTDProjectileVfxPresetJson.kt`
  - Remove JSON import path completely.
- Delete or repurpose `contents/data/config/astd_projectile_vfx_presets/aod7_shot.json`
  - Only after tests prove Kotlin migration preserves runtime values.
- Modify `tools/projectile-vfx-preview/src/export/kotlinExport.ts`
  - Export Kotlin component specs rather than old top-level preset fields.
- Remove or disable `tools/projectile-vfx-preview/src/export/gameExport.ts`, `gameExportCli.ts`, and `scripts/write-default-game-export.mjs`
  - Replace `export:game` with a Kotlin export/check command or remove the script.
- Update tests under `src/test/kotlin/cn/kasuminova/astd/renderer/projectile/`
  - Replace JSON catalog compatibility tests with Kotlin component catalog tests.

## Round 2 File Structure

- Create `tools/beam-vfx-preview/package.json`
  - Separate Vite React tool to avoid destabilizing projectile editor.
- Create `tools/beam-vfx-preview/src/model/beamPreset.ts`
  - Beam preset model: layers, control points, colors, widths, bloom, noise, blend.
- Create `tools/beam-vfx-preview/src/render/beamGeometry.ts`
  - Straight and curved beam sampling, normals, widths, UV/progress values.
- Create `tools/beam-vfx-preview/src/render/beamPreviewRenderer.ts`
  - Canvas/WebGL renderer for layered beams, bloom approximation, noise fill.
- Create `tools/beam-vfx-preview/src/export/kotlinBeamExport.ts`
  - Kotlin component parameter export for future runtime `Beam` component.
- Create `tools/beam-vfx-preview/src/ui/BeamEditor.tsx`
  - Layer stack, straight/curved toggle, control point controls, layer editor.
- Create `tools/beam-vfx-preview/src/ui/BeamPreviewCanvas.tsx`
  - Browser-verifiable preview surface.
- Add tests under `tools/beam-vfx-preview/src/**/*.test.ts(x)`
  - Geometry, export, and UI behavior tests.

---

## Round 1: Kotlin-First Component Runtime

### Task 1: Lock Current Behavior With Migration Tests

**Files:**
- Create: `src/test/kotlin/cn/kasuminova/astd/renderer/projectile/ASTDProjectileVfxComponentMigrationTest.kt`
- Modify: existing tests that directly assert top-level preset lists.

- [ ] **Step 1: Write failing tests for component shape**

Assert `aod7_shot` exposes ordered components:

```kotlin
@Test
fun `aod7 preset is represented by ordered render components`() {
    val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
    assertEquals(
        listOf("trail", "glow", "body", "sideWisp", "head", "ribbon"),
        preset.components.map { it.kind },
    )
}
```

- [ ] **Step 2: Add value preservation tests**

Check key migrated values from the current JSON/Kotlin state:

```kotlin
@Test
fun `aod7 migrated trail keeps authored dimensions and sprites`() {
    val preset = ASTDProjectileVfxPresetCatalog.preset("aod7_shot")!!
    val trail = preset.components.filterIsInstance<ASTDProjectileVfxComponentSpec.Trail>().single()
    assertEquals(420f, trail.layer.length)
    assertEquals(40f, trail.layer.startWidth)
    assertEquals(4f, trail.layer.endWidth)
    assertEquals("graphics/fx/beamcoreb.png", trail.layer.diffuseSpritePath)
}
```

- [ ] **Step 3: Run focused tests and verify failure**

Run:

```bash
./gradlew test --tests '*ASTDProjectileVfxComponentMigrationTest'
```

Expected: FAIL because `components` does not exist yet.

- [ ] **Step 4: Commit test checkpoint**

```bash
git add src/test/kotlin/cn/kasuminova/astd/renderer/projectile/ASTDProjectileVfxComponentMigrationTest.kt
git commit -m "test: define projectile vfx component migration contract"
```

### Task 2: Introduce Component Specs

**Files:**
- Modify: `src/main/kotlin/cn/kasuminova/astd/renderer/projectile/ASTDProjectileVfxPreset.kt`
- Create: `src/main/kotlin/cn/kasuminova/astd/renderer/projectile/component/ASTDProjectileVfxComponentSpec.kt`

- [ ] **Step 1: Add component sealed hierarchy**

Define component specs with stable ids and anchor references:

```kotlin
sealed interface ASTDProjectileVfxComponentSpec {
    val id: String
    val kind: String
    val enabled: Boolean

    data class Trail(
        override val id: String,
        val layer: ASTDTrailLayerSpec,
        val orientationMode: ASTDProjectileVfxOrientationMode = ASTDProjectileVfxOrientationMode.ProjectileVelocity,
        val anchorMode: ASTDProjectileVfxAnchorMode = ASTDProjectileVfxAnchorMode.HeadLocked,
        override val enabled: Boolean = true,
    ) : ASTDProjectileVfxComponentSpec {
        override val kind: String = "trail"
    }

    data class Ribbon(
        override val id: String,
        val trailId: String,
        val ribbons: List<ASTDTrailRibbonDecorationSpec>,
        override val enabled: Boolean = true,
    ) : ASTDProjectileVfxComponentSpec {
        override val kind: String = "ribbon"
    }
}
```

Then add `Head`, `Glow`, `Mist`, `SideWisp`, and `Extra` in the same file.

- [ ] **Step 2: Add `components` to preset**

Change `ASTDProjectileVfxPreset` to:

```kotlin
data class ASTDProjectileVfxPreset(
    val id: String,
    val components: List<ASTDProjectileVfxComponentSpec>,
    val samplingPolicy: ASTDProjectileVfxSamplingPolicy,
    val fadePolicy: ASTDProjectileVfxFadePolicy,
    val lifecycle: ASTDProjectileVfxLifecycleSpec = ASTDProjectileVfxLifecycleSpec(),
)
```

Temporarily keep compatibility helpers only if needed for compilation, but do not leave them as public runtime path at the end of Round 1.

- [ ] **Step 3: Run compile and collect breakages**

Run:

```bash
./gradlew compileKotlin
```

Expected: FAIL at direct references to `trailEntities`, `headLayers`, etc.

- [ ] **Step 4: Commit compile-breaking checkpoint only if using a branch/worktree**

If working in a shared main checkout, skip this commit and continue to Task 3 before committing.

### Task 3: Migrate AOD7 And Built-In Presets To Components

**Files:**
- Modify: `src/main/kotlin/cn/kasuminova/astd/renderer/projectile/ASTDProjectileVfxPresetCatalog.kt`

- [ ] **Step 1: Replace `gameExportPresetOrFallback` for AOD7**

Remove JSON lookup from catalog construction. Build `aod7_shot` from Kotlin component specs directly.

- [ ] **Step 2: Convert current `trailEntities.first()` data into `Trail` component**

Use current `ASTDTrailLayerSpec` values unchanged.

- [ ] **Step 3: Convert `glowLayers`, `headLayers`, `mistLayers`, `sideWispLayers`, `ribbonDecorations` into anchored components**

Each non-trail component references the trail component by `trailId = "astd_default_trail"`.

- [ ] **Step 4: Convert simple generated presets**

Replace old `layers: List<ASTDProjectileVfxLayer>` generation with component presets. If the old generic presets only need a BoxUtil trail, emit one `Trail` plus optional `Ribbon`/`Head` components.

- [ ] **Step 5: Run compile**

Run:

```bash
./gradlew compileKotlin
```

Expected: Remaining failures are render graph/runtime references, not catalog construction.

### Task 4: Build Component Registry And Render Context Resolution

**Files:**
- Create: `src/main/kotlin/cn/kasuminova/astd/renderer/projectile/component/ASTDProjectileVfxComponentContext.kt`
- Create: `src/main/kotlin/cn/kasuminova/astd/renderer/projectile/component/ASTDProjectileVfxComponentRegistry.kt`
- Modify: `src/main/kotlin/cn/kasuminova/astd/renderer/projectile/runtime/ASTDProjectileVfxRenderGraph.kt`

- [ ] **Step 1: Implement component context**

Context resolves trail components by id and throws if a dependent component references a missing trail:

```kotlin
class ASTDProjectileVfxComponentContext(
    components: List<ASTDProjectileVfxComponentSpec>,
) {
    private val trails = components.filterIsInstance<ASTDProjectileVfxComponentSpec.Trail>().associateBy { it.id }

    fun trail(id: String): ASTDProjectileVfxComponentSpec.Trail =
        requireNotNull(trails[id]) { "Projectile VFX component references missing trailId=$id" }
}
```

- [ ] **Step 2: Implement registry factories**

Registry maps:

- `Trail` -> `ASTDProjectileVfxTrailRenderLayer`
- `Glow` -> `ASTDProjectileVfxGlowRenderLayer`
- `Body` or trail body component -> `ASTDProjectileVfxBodyRenderLayer`
- `Head` -> `ASTDProjectileVfxHeadRenderLayer`
- `Ribbon` -> `ASTDProjectileVfxRibbonRenderLayer`
- `Mist` -> `ASTDProjectileVfxMistRenderLayer`
- `SideWisp` -> `ASTDProjectileVfxSideWispRenderLayer`
- `Extra` -> explicit exception until implemented.

- [ ] **Step 3: Replace hardcoded render graph assembly**

`layersFor(preset)` should iterate `preset.components.filter { enabled }` and delegate to registry. Debug visibility may suppress a known component kind, but should not decide graph structure from top-level lists.

- [ ] **Step 4: Add registry unit tests**

Create tests for:

- component order is preserved.
- missing anchor trail throws.
- disabled components do not instantiate render layers.
- `Extra` without registered factory throws.

- [ ] **Step 5: Run focused tests**

```bash
./gradlew test --tests '*ASTDProjectileVfxRenderGraphTest' --tests '*ASTDProjectileVfxComponent*'
```

Expected: PASS after renderer constructors are adapted.

### Task 5: Adapt Existing Renderers To Component Specs

**Files:**
- Modify: `ASTDProjectileVfxTrailRenderer.kt`
- Modify: `ASTDProjectileVfxBodyRenderer.kt`
- Modify: `ASTDProjectileVfxGlowRenderer.kt`
- Modify: `ASTDProjectileVfxHeadRenderer.kt`
- Modify: `ASTDProjectileVfxMistRenderer.kt`
- Modify: `ASTDProjectileVfxRibbonRenderer.kt`
- Modify: `ASTDProjectileVfxSideWispRenderer.kt`

- [ ] **Step 1: Create conversion helpers**

Provide a private or internal helper that converts a `Trail` component into the existing `ASTDTrailEntitySpec` until renderers are fully renamed:

```kotlin
internal fun ASTDProjectileVfxComponentSpec.Trail.toTrailEntitySpec(): ASTDTrailEntitySpec = ASTDTrailEntitySpec(
    layerId = id,
    id = id,
    nodes = emptyList(),
    layerSpec = layer,
    layers = listOf(layer),
    orientationMode = orientationMode,
    anchorMode = anchorMode,
)
```

- [ ] **Step 2: Keep renderer math unchanged**

Do not visually tune in this migration task. The only allowed renderer changes are constructor inputs and data access.

- [ ] **Step 3: Run current parity/unit tests**

```bash
./gradlew test --tests '*ASTDProjectileVfx*RendererTest' --tests '*ASTDProjectileVfxLayoutParityTest'
```

Expected: Existing numeric expectations either pass or require mechanical updates from field access only.

- [ ] **Step 4: Commit component runtime migration**

```bash
git add src/main/kotlin/cn/kasuminova/astd/renderer/projectile src/test/kotlin/cn/kasuminova/astd/renderer/projectile
git commit -m "refactor: migrate projectile vfx runtime to components"
```

### Task 6: Remove JSON Import Path

**Files:**
- Delete: `src/main/kotlin/cn/kasuminova/astd/renderer/projectile/ASTDProjectileVfxPresetJson.kt`
- Delete or archive: `contents/data/config/astd_projectile_vfx_presets/aod7_shot.json`
- Modify: tests referencing `loadGameExportPresetForTest`
- Modify: `tools/projectile-vfx-preview/src/model/aod7Preset.ts`
- Modify: `tools/projectile-vfx-preview/src/export/gameExport.ts`
- Modify: `tools/projectile-vfx-preview/package.json`

- [ ] **Step 1: Delete JSON parser and catalog fallback**

Remove all references to `ASTDProjectileVfxPresetJson`.

- [ ] **Step 2: Replace JSON compatibility tests**

Remove tests that assert the catalog loads frontend JSON. Replace them with tests that assert Kotlin catalog contains all mapped preset ids from `contents/data/config/astd_projectile_vfx.json`.

- [ ] **Step 3: Remove `export:game` JSON writer**

Remove or rename the script so it cannot imply direct JSON-to-game support.

- [ ] **Step 4: Keep TS editor baseline decoupled**

For projectile editor tests that need AOD7 data, either:

- move a TS-only fixture under `tools/projectile-vfx-preview/src/fixtures/aod7Preset.ts`, or
- derive from the TS default preset.

Do not import deleted game JSON.

- [ ] **Step 5: Search for stale JSON path references**

Run:

```bash
rg -n "ASTDProjectileVfxPresetJson|astd_projectile_vfx_presets|export:game|gameExportPresetOrFallback|loadGameExportPresetForTest" .
```

Expected: no runtime references; docs may mention removal only if updated.

- [ ] **Step 6: Run full Kotlin and TS tests**

```bash
./gradlew test
cd tools/projectile-vfx-preview && npm test -- --run
```

Expected: PASS.

- [ ] **Step 7: Commit JSON removal**

```bash
git add -A
git commit -m "refactor: remove projectile vfx json import path"
```

### Task 7: Add Kotlin Hot Reload For Projectile Effects

**Files:**
- Create: `src/main/kotlin/cn/kasuminova/astd/renderer/projectile/reload/ASTDProjectileVfxHotReloadSource.kt`
- Create: `src/main/kotlin/cn/kasuminova/astd/renderer/projectile/reload/ASTDProjectileVfxHotReloadManager.kt`
- Modify: `ASTDProjectileVfxPresetCatalog.kt`
- Modify: `ASTDProjectileVfxRuntimeManager.kt`
- Add tests under `src/test/kotlin/cn/kasuminova/astd/renderer/projectile/reload/`

- [ ] **Step 1: Define hot reload boundary**

Initial implementation should support Kotlin-side hot reload as a development preset source, not runtime Kotlin compilation inside the game.

Use a provider interface:

```kotlin
interface ASTDProjectileVfxHotReloadSource {
    fun version(): Long
    fun presets(): Map<String, ASTDProjectileVfxPreset>
}
```

Production source returns built-in Kotlin presets. Development source can be swapped in tests and later wired to script/classloader work.

- [ ] **Step 2: Make catalog refreshable**

Catalog should expose:

```kotlin
fun reloadForDev(source: ASTDProjectileVfxHotReloadSource): Int
fun version(): Long
```

Reload replaces the preset map atomically and throws on duplicate ids or invalid components.

- [ ] **Step 3: Runtime manager picks up changed preset versions**

When a projectile starts, it uses current catalog data. Existing active projectiles should not mutate mid-flight in first implementation unless explicitly reset.

- [ ] **Step 4: Add tests**

Test:

- reload replaces preset for future projectiles.
- duplicate id throws.
- invalid missing trail anchor throws.
- no JSON fallback occurs.

- [ ] **Step 5: Run reload tests**

```bash
./gradlew test --tests '*HotReload*' --tests '*ProjectileVfxRegistryRuntimeTest'
```

Expected: PASS.

- [ ] **Step 6: Commit hot reload boundary**

```bash
git add src/main/kotlin/cn/kasuminova/astd/renderer/projectile src/test/kotlin/cn/kasuminova/astd/renderer/projectile
git commit -m "feat: add projectile vfx kotlin hot reload boundary"
```

### Task 8: Update Projectile Editor Kotlin Export

**Files:**
- Modify: `tools/projectile-vfx-preview/src/export/kotlinExport.ts`
- Modify: `tools/projectile-vfx-preview/src/export/kotlinExport.test.ts`
- Modify: `tools/projectile-vfx-preview/src/model/gameExport.ts` or remove if no longer needed.
- Modify: `tools/projectile-vfx-preview/src/ui/ConfigPanel.tsx`

- [ ] **Step 1: Export component Kotlin**

Output should produce `ASTDProjectileVfxPreset(components = listOf(...))`.

- [ ] **Step 2: Rename UI labels**

Change labels from `Game Export JSON` to Kotlin-oriented terms, for example:

- `Export Kotlin Component Preset`
- `Copy Kotlin`

- [ ] **Step 3: Remove JSON game export from UI**

Keep import/export JSON only if it is explicitly preview-local. Label it `Preview JSON`, not `Game Export`.

- [ ] **Step 4: Run TS tests**

```bash
cd tools/projectile-vfx-preview
npm test -- --run
```

Expected: PASS.

- [ ] **Step 5: Commit editor export migration**

```bash
git add tools/projectile-vfx-preview
git commit -m "feat: export projectile vfx kotlin components from preview"
```

---

## Round 2: Beam VFX Editor Prototype

### Task 9: Scaffold Beam Editor Tool

**Files:**
- Create: `tools/beam-vfx-preview/package.json`
- Create: `tools/beam-vfx-preview/index.html`
- Create: `tools/beam-vfx-preview/tsconfig.json`
- Create: `tools/beam-vfx-preview/vite.config.ts`
- Create: `tools/beam-vfx-preview/src/main.tsx`
- Create: `tools/beam-vfx-preview/src/App.tsx`
- Create: `tools/beam-vfx-preview/src/App.css`
- Create: `tools/beam-vfx-preview/src/test/setup.ts`

- [ ] **Step 1: Scaffold minimal Vite React app**

Mirror the lightweight dependency pattern from `tools/projectile-vfx-preview`.

- [ ] **Step 2: Add scripts**

```json
{
  "scripts": {
    "dev": "vite",
    "build": "tsc && vite build",
    "test": "vitest",
    "test:run": "vitest run"
  }
}
```

- [ ] **Step 3: Run build**

```bash
cd tools/beam-vfx-preview
npm install
npm run build
```

Expected: PASS.

- [ ] **Step 4: Commit scaffold**

```bash
git add tools/beam-vfx-preview
git commit -m "feat: scaffold beam vfx preview editor"
```

### Task 10: Implement Beam Data Model And Geometry

**Files:**
- Create: `tools/beam-vfx-preview/src/model/beamPreset.ts`
- Create: `tools/beam-vfx-preview/src/render/beamGeometry.ts`
- Create: `tools/beam-vfx-preview/src/render/beamGeometry.test.ts`

- [ ] **Step 1: Define beam preset model**

Model includes:

- `mode: 'straight' | 'curved'`
- `controlPoints`
- `layers[]`
- per layer: `widthStart`, `widthEnd`, `colorStart`, `colorEnd`, `emissiveStart`, `emissiveEnd`, `textureSpeed`, `noiseStrength`, `noiseScale`, `bloomStrength`, `blendMode`.

- [ ] **Step 2: Implement straight sampling**

Straight mode samples from start to end with stable progress `0..1`.

- [ ] **Step 3: Implement curved sampling**

Curved mode samples quadratic or cubic Bezier control points. Normals must be continuous.

- [ ] **Step 4: Add geometry tests**

Test:

- straight endpoints match input.
- curved midpoint is displaced from straight line.
- normals are finite.
- sample count remains stable for same quality setting.

- [ ] **Step 5: Run tests**

```bash
cd tools/beam-vfx-preview
npm test -- --run src/render/beamGeometry.test.ts
```

Expected: PASS.

- [ ] **Step 6: Commit geometry**

```bash
git add tools/beam-vfx-preview/src/model tools/beam-vfx-preview/src/render
git commit -m "feat: add beam vfx geometry model"
```

### Task 11: Implement Preview Renderer With Layering, Bloom Approximation, And Noise

**Files:**
- Create: `tools/beam-vfx-preview/src/render/beamPreviewRenderer.ts`
- Create: `tools/beam-vfx-preview/src/render/beamNoise.ts`
- Create: `tools/beam-vfx-preview/src/render/beamPreviewRenderer.test.ts`
- Modify: `tools/beam-vfx-preview/src/App.tsx`

- [ ] **Step 1: Draw layered beam mesh**

Use Canvas 2D initially unless WebGL is required. Draw each layer from tail to head using sampled quads or stroked segments.

- [ ] **Step 2: Add bloom approximation**

Use multiple wider translucent passes per beam layer. Keep it deterministic and inspectable.

- [ ] **Step 3: Add deterministic noise modulation**

Noise should alter alpha/color subtly along the beam and time, avoiding pure flat color. It must be stable frame-to-frame, not random refresh.

- [ ] **Step 4: Add tests for deterministic noise**

Same input/time must produce same result; nearby time values should change smoothly.

- [ ] **Step 5: Run tests**

```bash
cd tools/beam-vfx-preview
npm test -- --run
```

Expected: PASS.

- [ ] **Step 6: Commit renderer**

```bash
git add tools/beam-vfx-preview
git commit -m "feat: render layered beam vfx preview"
```

### Task 12: Build Beam Editor UI

**Files:**
- Create: `tools/beam-vfx-preview/src/ui/BeamEditor.tsx`
- Create: `tools/beam-vfx-preview/src/ui/BeamPreviewCanvas.tsx`
- Create: `tools/beam-vfx-preview/src/ui/BeamEditor.test.tsx`
- Modify: `tools/beam-vfx-preview/src/App.tsx`
- Modify: `tools/beam-vfx-preview/src/App.css`

- [ ] **Step 1: Add mode switch**

Segmented control: `Straight` / `Curved`.

- [ ] **Step 2: Add control point editor**

Straight mode shows start/end. Curved mode shows start/control/end at minimum.

- [ ] **Step 3: Add layer stack**

Support adding, duplicating, deleting, enabling/disabling, and reordering beam layers.

- [ ] **Step 4: Add parameter controls**

Controls:

- width start/end
- color start/end
- emissive start/end
- bloom strength
- noise strength/scale
- texture speed
- blend mode

- [ ] **Step 5: Add UI tests**

Test:

- switching to curved changes preview mode.
- adding layer increases layer count.
- changing noise strength updates preset state.
- disabled layer is not rendered.

- [ ] **Step 6: Browser validation**

Start dev server:

```bash
cd tools/beam-vfx-preview
npm run dev -- --host 127.0.0.1
```

Use chrome-devtools MCP to verify:

- page loads without console errors.
- straight mode draws a visible beam.
- curved mode draws a visible curved beam.
- two enabled layers visibly stack.

- [ ] **Step 7: Commit UI**

```bash
git add tools/beam-vfx-preview
git commit -m "feat: add beam vfx editor controls"
```

### Task 13: Kotlin Beam Export Contract

**Files:**
- Create: `tools/beam-vfx-preview/src/export/kotlinBeamExport.ts`
- Create: `tools/beam-vfx-preview/src/export/kotlinBeamExport.test.ts`
- Create: `src/main/kotlin/cn/kasuminova/astd/renderer/projectile/component/ASTDBeamVfxComponentSpec.kt` if runtime component is introduced now.

- [ ] **Step 1: Define export target**

Export TS beam preset to Kotlin component parameter text. If runtime beam component is not implemented in this round, export a clearly named draft type:

```kotlin
ASTDBeamVfxDraftPreset(...)
```

Do not pretend it is wired to game runtime until the runtime component exists.

- [ ] **Step 2: Add tests**

Ensure export contains:

- mode
- control points
- multiple beam layers
- noise parameters
- bloom parameters

- [ ] **Step 3: Run TS tests**

```bash
cd tools/beam-vfx-preview
npm test -- --run src/export/kotlinBeamExport.test.ts
```

Expected: PASS.

- [ ] **Step 4: Commit export contract**

```bash
git add tools/beam-vfx-preview src/main/kotlin/cn/kasuminova/astd/renderer/projectile/component
git commit -m "feat: export beam vfx kotlin parameters"
```

### Task 14: Optional Runtime Beam Component Spike

Only start this task if Round 1 is complete and the beam editor prototype is stable.

**Files:**
- Create: `src/main/kotlin/cn/kasuminova/astd/renderer/beam/ASTDBeamVfxPreset.kt`
- Create: `src/main/kotlin/cn/kasuminova/astd/renderer/beam/ASTDBeamVfxRenderer.kt`
- Create: `src/main/kotlin/cn/kasuminova/astd/renderer/beam/ASTDBeamVfxGeometry.kt`
- Test: `src/test/kotlin/cn/kasuminova/astd/renderer/beam/ASTDBeamVfxGeometryTest.kt`

- [ ] **Step 1: Read BoxUtil CurveEntity API from current installed version**

Use current workspace/game dependency sources or jar, not online docs.

- [ ] **Step 2: Implement geometry-only tests first**

Match TS beam sampling contract: straight and curved sample points.

- [ ] **Step 3: Add BoxUtil CurveEntity wrapper**

Fail loudly on addEntity failure. No original-rendering fallback.

- [ ] **Step 4: Add minimal in-game debug scenario**

Use one straight beam and one curved beam with two layers.

- [ ] **Step 5: Run tests**

```bash
./gradlew test --tests '*ASTDBeamVfx*'
```

Expected: PASS.

- [ ] **Step 6: Commit runtime spike**

```bash
git add src/main/kotlin/cn/kasuminova/astd/renderer/beam src/test/kotlin/cn/kasuminova/astd/renderer/beam
git commit -m "feat: add boxutil beam vfx runtime spike"
```

---

## Verification Matrix

Run before considering Round 1 complete:

```bash
./gradlew test
cd tools/projectile-vfx-preview && npm test -- --run
rg -n "ASTDProjectileVfxPresetJson|gameExportPresetOrFallback|astd_projectile_vfx_presets|export:game" .
```

Expected:

- Gradle tests pass.
- Projectile preview tests pass.
- Search returns no runtime JSON import path.

Run before considering Round 2 complete:

```bash
cd tools/beam-vfx-preview
npm run build
npm test -- --run
```

Then validate in browser through chrome-devtools MCP:

- straight beam visible.
- curved beam visible.
- layer stacking visible.
- noise changes appearance without frame-random popping.
- no console errors.

## Commit Checkpoints

1. `test: define projectile vfx component migration contract`
2. `refactor: migrate projectile vfx runtime to components`
3. `refactor: remove projectile vfx json import path`
4. `feat: add projectile vfx kotlin hot reload boundary`
5. `feat: export projectile vfx kotlin components from preview`
6. `feat: scaffold beam vfx preview editor`
7. `feat: add beam vfx geometry model`
8. `feat: render layered beam vfx preview`
9. `feat: add beam vfx editor controls`
10. `feat: export beam vfx kotlin parameters`
11. Optional: `feat: add boxutil beam vfx runtime spike`

## Risk Notes

- Removing JSON import will break tests and editor assumptions until TS fixtures/export are migrated. Do this in one focused checkpoint.
- Kotlin hot reload should first mean reloadable preset provider, not in-game Kotlin compilation. Full classloader/script hot reload is a separate risk-heavy feature.
- Component registry must not silently skip unknown components. Unknown or invalid component specs should throw.
- Existing renderers frequently assume a single primary trail. Component anchoring fixes this incrementally by making `trailId` explicit.
- Beam editor should remain a separate tool initially. Folding it into projectile preview too early will increase coupling before the beam runtime contract is proven.
