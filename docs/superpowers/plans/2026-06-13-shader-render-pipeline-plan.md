# Shader Render Pipeline Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a reusable in-combat shader VFX host and migrate Arc Jet's shockwave ring onto it while preserving the current visual behavior.

**Architecture:** Add a small public shader VFX API under `cn.kasuminova.astd.renderer.shader.base`, internal runtime infrastructure under `cn.kasuminova.astd.renderer.shader.runtime`, and domain contracts under `cn.kasuminova.astd.renderer.shader.domain`. The first production consumer is Arc Jet's shockwave ring, replacing its current one-off layered renderer with a `WorldQuad` shader submission path.

**Tech Stack:** Kotlin, Starsector combat rendering API, LWJGL OpenGL 1.1/2.0, Gradle test suite, existing in-game VFX smoke automation.

---

## Scope Boundaries

- First-round production migration is only Arc Jet `shockwave_ring`.
- Projectile, beam, missile, and trail shader interfaces are introduced as contracts, but their existing VFX implementations are not rewritten in this pass.
- New class names do not use the `ASTD` prefix. The package name already provides the mod namespace.
- Do not introduce `Service`, `Manager`, or `Controller` class names.
- Do not add vanilla rendering fallbacks for shader failures.
- Do not use reflection in new tests or implementation.

## Worktree Hygiene

The workspace currently contains unrelated documentation migration changes:

- Deleted legacy `.agents/superpower/...` files.
- Added replacement `docs/superpowers/...` files.
- Added `.agents/AGENTS.md`.

These must be committed or explicitly isolated before shader implementation is staged.

## Planned Files

### Public API

- Create: `src/main/kotlin/cn/kasuminova/astd/renderer/shader/base/ShaderEffectSpec.kt`
- Create: `src/main/kotlin/cn/kasuminova/astd/renderer/shader/base/ShaderProgramSpec.kt`
- Create: `src/main/kotlin/cn/kasuminova/astd/renderer/shader/base/ShaderUniforms.kt`
- Create: `src/main/kotlin/cn/kasuminova/astd/renderer/shader/base/ShaderGeometrySpec.kt`
- Create: `src/main/kotlin/cn/kasuminova/astd/renderer/shader/base/ShaderMaterialSpec.kt`
- Create: `src/main/kotlin/cn/kasuminova/astd/renderer/shader/base/ShaderEffectKey.kt`
- Create: `src/main/kotlin/cn/kasuminova/astd/renderer/shader/base/ShaderHandle.kt`
- Create: `src/main/kotlin/cn/kasuminova/astd/renderer/shader/base/ShaderEffectCatalog.kt`

### Runtime

- Create: `src/main/kotlin/cn/kasuminova/astd/renderer/shader/runtime/ShaderSink.kt`
- Create: `src/main/kotlin/cn/kasuminova/astd/renderer/shader/runtime/ShaderSubmission.kt`
- Create: `src/main/kotlin/cn/kasuminova/astd/renderer/shader/runtime/CombatShaderRuntime.kt`
- Create: `src/main/kotlin/cn/kasuminova/astd/renderer/shader/runtime/ShaderRenderQueue.kt`
- Create: `src/main/kotlin/cn/kasuminova/astd/renderer/shader/runtime/ShaderLifecycle.kt`
- Create: `src/main/kotlin/cn/kasuminova/astd/renderer/shader/runtime/ShaderProgramCache.kt`
- Create: `src/main/kotlin/cn/kasuminova/astd/renderer/shader/runtime/ShaderStateGuard.kt`
- Create: `src/main/kotlin/cn/kasuminova/astd/renderer/shader/runtime/ShaderLayerPlugin.kt`
- Create: `src/main/kotlin/cn/kasuminova/astd/renderer/shader/runtime/ViewportContext.kt`

### Domain Contracts

- Create: `src/main/kotlin/cn/kasuminova/astd/renderer/shader/domain/ShipSystemShaderEffect.kt`
- Create: `src/main/kotlin/cn/kasuminova/astd/renderer/shader/domain/ProjectileShaderEffect.kt`
- Create: `src/main/kotlin/cn/kasuminova/astd/renderer/shader/domain/BeamShaderEffect.kt`
- Create: `src/main/kotlin/cn/kasuminova/astd/renderer/shader/domain/MissileShaderEffect.kt`
- Create: `src/main/kotlin/cn/kasuminova/astd/renderer/shader/domain/TrailShaderEffect.kt`

### Arc Jet Migration

- Replace: `src/main/kotlin/cn/kasuminova/astd/renderer/effect/system/ASTDArcJetShockwaveRingRenderer.kt`
- Modify: `src/main/kotlin/cn/kasuminova/astd/combat/hullmods/arc/ASTDArcProductionVfx.kt`
- Replace/rename test intent: `src/test/kotlin/cn/kasuminova/astd/renderer/effect/system/ASTDArcJetShockwaveRingSpecTest.kt`

### Tests

- Create: `src/test/kotlin/cn/kasuminova/astd/renderer/shader/base/ShaderUniformsTest.kt`
- Create: `src/test/kotlin/cn/kasuminova/astd/renderer/shader/base/ShaderEffectCatalogTest.kt`
- Create: `src/test/kotlin/cn/kasuminova/astd/renderer/shader/runtime/ShaderRenderQueueTest.kt`
- Create: `src/test/kotlin/cn/kasuminova/astd/renderer/shader/runtime/ShaderLifecycleTest.kt`
- Create: `src/test/kotlin/cn/kasuminova/astd/renderer/shader/runtime/CombatShaderRuntimeTest.kt`
- Create: `src/test/kotlin/cn/kasuminova/astd/renderer/effect/system/ArcJetShockwaveRingEffectTest.kt`

---

## Task 0: Isolate Existing Documentation Migration

**Files:**
- Stage existing `.agents` and `docs/superpowers` migration files only.
- Do not stage shader plan or implementation files in this task.

- [ ] **Step 1: Inspect current status**

Run: `git status --short`

Expected: only the already-present documentation migration and the new plan file are dirty.

- [ ] **Step 2: Stage documentation migration only**

Run: `git add .agents docs/superpowers`

Then unstage the shader plan if it was included:

Run: `git restore --staged docs/superpowers/plans/2026-06-13-shader-render-pipeline-plan.md`

- [ ] **Step 3: Review staged set**

Run: `git diff --cached --name-status`

Expected: only `.agents` and previous `docs/superpowers` migration files, not shader code.

- [ ] **Step 4: Commit documentation migration**

Run: `git commit -m "docs: migrate agent superpower records"`

Expected: commit succeeds; shader plan remains unstaged or uncommitted for the next task.

---

## Task 1: Commit This Implementation Plan

**Files:**
- Create: `docs/superpowers/plans/2026-06-13-shader-render-pipeline-plan.md`

- [ ] **Step 1: Review plan diff**

Run: `git diff -- docs/superpowers/plans/2026-06-13-shader-render-pipeline-plan.md`

Expected: this implementation plan only.

- [ ] **Step 2: Stage and commit plan**

Run:

```bash
git add docs/superpowers/plans/2026-06-13-shader-render-pipeline-plan.md
git commit -m "docs: plan shader render pipeline"
```

Expected: commit succeeds.

---

## Task 2: Add Shader API Contracts

**Files:**
- Create public API files under `src/main/kotlin/cn/kasuminova/astd/renderer/shader/base`
- Create tests under `src/test/kotlin/cn/kasuminova/astd/renderer/shader/base`

- [ ] **Step 1: Write failing tests for uniform schema validation**

Test behaviors:

- duplicate uniform keys are rejected.
- required uniforms must be present.
- unknown uniform keys are rejected.
- defaults are applied only for optional uniforms.
- type mismatches fail with a clear exception.

Run: `./gradlew test --tests "cn.kasuminova.astd.renderer.shader.base.ShaderUniformsTest"`

Expected: FAIL because the API does not exist.

- [ ] **Step 2: Implement uniform API contracts**

Create:

- `ShaderUniformType`
- `ShaderUniformSpec`
- `ShaderUniformSchema`
- `ShaderUniformSet`

Implementation requirements:

- fail fast on invalid schema or values.
- include effect/program/uniform id in exception messages where available.
- no silent catch blocks.

- [ ] **Step 3: Verify uniform tests pass**

Run: `./gradlew test --tests "cn.kasuminova.astd.renderer.shader.base.ShaderUniformsTest"`

Expected: PASS.

- [ ] **Step 4: Write failing tests for catalog registration**

Test behaviors:

- duplicate effect ids are rejected.
- lookup returns the registered spec.
- unknown lookup returns null.

Run: `./gradlew test --tests "cn.kasuminova.astd.renderer.shader.base.ShaderEffectCatalogTest"`

Expected: FAIL until catalog exists.

- [ ] **Step 5: Implement remaining API contracts**

Create:

- `ShaderEffectSpec`
- `ShaderProgramSpec`
- `ShaderGeometrySpec`
- `ShaderMaterialSpec`
- `ShaderEffectKey`
- `ShaderHandle`
- `ShaderEffectCatalog`

- [ ] **Step 6: Verify API tests**

Run: `./gradlew test --tests "cn.kasuminova.astd.renderer.shader.base.*"`

Expected: PASS.

- [ ] **Step 7: Commit API contracts**

Run:

```bash
git add src/main/kotlin/cn/kasuminova/astd/renderer/shader/base src/test/kotlin/cn/kasuminova/astd/renderer/shader/base
git commit -m "feat(render): add shader effect contracts"
```

---

## Task 3: Add Runtime Host and Queue

**Files:**
- Create runtime files under `src/main/kotlin/cn/kasuminova/astd/renderer/shader/runtime`
- Create runtime tests under `src/test/kotlin/cn/kasuminova/astd/renderer/shader/runtime`

- [ ] **Step 1: Write failing queue tests**

Test behaviors:

- submissions are grouped by combat layer.
- sorting is stable by render order, program id, material, and geometry.
- snapshot data is copied at submission time.

Run: `./gradlew test --tests "cn.kasuminova.astd.renderer.shader.runtime.ShaderRenderQueueTest"`

Expected: FAIL until queue exists.

- [ ] **Step 2: Implement render queue and submission snapshot**

Create:

- `ShaderSubmission`
- `ShaderRenderQueue`
- `ViewportContext`

Implementation requirements:

- no Starsector engine dependency in queue tests.
- immutable snapshots where practical.

- [ ] **Step 3: Verify queue tests pass**

Run: `./gradlew test --tests "cn.kasuminova.astd.renderer.shader.runtime.ShaderRenderQueueTest"`

Expected: PASS.

- [ ] **Step 4: Write failing lifecycle/runtime tests**

Test behaviors:

- `emit` submissions expire after lifetime.
- `upsert` replaces the previous snapshot for the same key.
- `remove` deletes active keyed submissions.
- one runtime is installed per engine wrapper.
- cleanup expires handles and clears active state.

Run: `./gradlew test --tests "cn.kasuminova.astd.renderer.shader.runtime.*"`

Expected: FAIL for missing runtime pieces.

- [ ] **Step 5: Implement runtime host**

Create:

- `ShaderSink`
- `CombatShaderRuntime`
- `ShaderLifecycle`
- `ShaderLayerPlugin`
- `ShaderProgramCache`
- `ShaderStateGuard`

Implementation requirements:

- production engine integration uses Starsector `CombatEngineAPI`.
- tests use a small fake host boundary, not reflection or dynamic proxies.
- shader compile/link failures include program id and shader stage.
- GL state guard owns state save/restore.

- [ ] **Step 6: Verify runtime tests**

Run: `./gradlew test --tests "cn.kasuminova.astd.renderer.shader.runtime.*"`

Expected: PASS.

- [ ] **Step 7: Commit runtime host**

Run:

```bash
git add src/main/kotlin/cn/kasuminova/astd/renderer/shader/runtime src/test/kotlin/cn/kasuminova/astd/renderer/shader/runtime
git commit -m "feat(render): add shader runtime host"
```

---

## Task 4: Add Shader Domain Contracts

**Files:**
- Create domain files under `src/main/kotlin/cn/kasuminova/astd/renderer/shader/domain`

- [ ] **Step 1: Add domain interfaces with KDoc**

Create:

- `ShipSystemShaderEffect`
- `ProjectileShaderEffect`
- `BeamShaderEffect`
- `MissileShaderEffect`
- `TrailShaderEffect`
- `TrailEmitter`

Requirements:

- each interface explains its lifecycle and key ownership.
- projectile contract references projectile-spec-id dispatch.
- beam contract documents per-frame `upsert` plus stale cleanup.
- trail contract documents control points and BoxUtil priority.

- [ ] **Step 2: Compile domain contracts**

Run: `./gradlew compileKotlin`

Expected: PASS.

- [ ] **Step 3: Commit domain contracts**

Run:

```bash
git add src/main/kotlin/cn/kasuminova/astd/renderer/shader/domain
git commit -m "feat(render): add shader domain contracts"
```

---

## Task 5: Migrate Arc Jet Shockwave Ring

**Files:**
- Replace: `src/main/kotlin/cn/kasuminova/astd/renderer/effect/system/ASTDArcJetShockwaveRingRenderer.kt`
- Modify: `src/main/kotlin/cn/kasuminova/astd/combat/hullmods/arc/ASTDArcProductionVfx.kt`
- Replace test: `src/test/kotlin/cn/kasuminova/astd/renderer/effect/system/ASTDArcJetShockwaveRingSpecTest.kt`

- [ ] **Step 1: Write failing Arc Jet migration tests**

Test behaviors:

- Arc Jet effect spec keeps the reference shockwave parameters.
- frame calculation preserves current radius, pressure, exposure, and alpha behavior.
- render path submits a keyed world quad shader effect instead of owning a dedicated layered plugin.
- stale timeout is represented by shader lifecycle policy.

Run: `./gradlew test --tests "cn.kasuminova.astd.renderer.effect.system.*"`

Expected: FAIL until migration exists.

- [ ] **Step 2: Implement Arc Jet shader effect**

Create or replace with:

- `ArcJetShockwaveRingEffect`

Implementation requirements:

- keep the existing fragment shader output behavior.
- use `ShaderEffectSpec` with `WorldQuad` geometry and additive material.
- use `ShipSystemShaderEffect` or direct ship-system adapter to submit through `ShaderSink`.
- no dedicated `BaseCombatLayeredRenderingPlugin` remains in the Arc Jet effect.
- no old beam/star/path flare rendering reappears.

- [ ] **Step 3: Wire Arc Production VFX**

Modify `ASTDArcProductionVfx.renderArcJetShockwaveRing` to call the new effect path.

- [ ] **Step 4: Verify focused Arc Jet tests**

Run:

```bash
./gradlew test --tests "cn.kasuminova.astd.renderer.effect.system.*"
./gradlew test --tests "cn.kasuminova.astd.combat.hullmods.arc.ASTDArcProductionVfxTest"
```

Expected: PASS.

- [ ] **Step 5: Commit Arc Jet migration**

Run:

```bash
git add src/main/kotlin/cn/kasuminova/astd/renderer/effect/system src/main/kotlin/cn/kasuminova/astd/combat/hullmods/arc src/test/kotlin/cn/kasuminova/astd/renderer/effect/system
git commit -m "feat(combat): render arc jet shockwave through shader host"
```

---

## Task 6: Final Verification

**Files:**
- No planned file edits.

- [ ] **Step 1: Run shader and Arc Jet focused tests**

Run:

```bash
./gradlew test --tests "cn.kasuminova.astd.renderer.shader.*" --tests "cn.kasuminova.astd.renderer.effect.system.*" --tests "cn.kasuminova.astd.combat.hullmods.arc.ASTDArcProductionVfxTest"
```

Expected: PASS.

- [ ] **Step 2: Run automation verifier unit test**

Run: `python3 tools/verify_ingame_vfx_automation_test.py`

Expected: PASS.

- [ ] **Step 3: Run in-game smoke automation**

Run: `./gradlew smokeTestGame`

Expected: PASS and evidence still detects Arc Jet shockwave ring.

- [ ] **Step 4: Check formatting and staged state**

Run:

```bash
git diff --check
git status --short
```

Expected: no whitespace errors; only intentional post-commit files, if any.

- [ ] **Step 5: Final review checklist**

Confirm:

- Arc Jet old dedicated renderer is gone or reduced to a thin compatibility wrapper.
- Arc Jet no longer owns its own layered plugin.
- One shader runtime host is installed per combat engine.
- Shader API classes do not use the `ASTD` prefix.
- No new reflection-based tests.
- No vanilla rendering fallback branch.
