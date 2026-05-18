# ASTD Final Main Projectile VFX Migration Plan

**Goal:** 将主模块 projectile VFX 入口切换到第四轮新增的 `renderer.projectile` runtime，移除旧射弹渲染体系的主路径，并完成游戏内验证前的最终主模块收敛。

**Architecture:** 保留 `.proj` 的 `ProjectileSpecOnFireDispatcher` 入口；`ProjectileVfxRegistry` 从旧 `ProjectileSpawnHandler` 映射迁移为 `ASTDProjectileVfxPreset` 映射；运行时由统一 manager 每帧 advance / fade / dispose。旧 VFX 类按计划删除或退场，专用武器逻辑仅保留非渲染职责，如导弹 AI、on-hit 特效、特殊伤害逻辑。

**Tech Stack:** Kotlin, Java 17, Gradle, BoxUtil `TrailEntity`, Starsector Combat API, ss-csv safe generation

---

## Scope

本轮执行：

1. 引入 `ASTDProjectileVfxRuntimeManager`，统一管理 projectile runtime 生命周期。
2. 将 `ProjectileVfxRegistry` 从旧 handler 切换为 `ASTDProjectileVfxPreset` 查找。
3. 将 `ProjectileSpecOnFireDispatcher` 接入新 runtime manager。
4. 为 `contents/data/config/astd_projectile_vfx.json` 中所有 preset 建立 runtime preset。
5. 替换旧射弹渲染主路径：
   - `ProjectileTracerManager`
   - `ProjectileVfxPresets`
   - `CompositeProjectileVisual`
   - `CodeProjectileRenderer`
   - `TaperedBeamTrailsVfx` 的 projectile 主路径用途
6. 保留非渲染职责：
   - 导弹 AI 注入
   - on-hit / on-fire 中真实 gameplay 逻辑
   - 特殊武器机制
7. 清理 `.proj` / `.wpn` 中旧 renderer 类引用。
8. 补充完整回归测试与风险检索。

本轮范围外：

- SpriteEntity / FlareEntity / 实例粒子实现
- 前端新增编辑字段
- ss-csv 项目结构重构
- BoxUtil 源码修改
- 战斗内视觉人工调参到最终美术品质

---

## Responsibility boundary

### Runtime migration

迁移到新 runtime：

- 拖尾
- glow trail
- ribbon
- head trail
- projectile 可见性补偿

保留在现有 combat effect 包：

- 导弹 AI：
  - `ProjectileMissileAiInjector`
  - `Rct6TerminalCorrectionAI`
  - `Tsm2TerminalSprintAI`
  - `SingularityRetargetMissileAI`
- 专用 gameplay 逻辑：
  - on-hit effect
  - damage / debuff / CR 机制
  - 非渲染 combat logic

### Old path retirement

最终主路径不再调用：

- `ProjectileTracerManager.track(...)`
- `CodeProjectileRenderer.onSpawn(...)`
- `ProjectileVfxPresets.*.onSpawn(...)`
- `CompositeProjectileVisual(...)`

旧文件可临时保留到下一轮删除清理，但主路径不可引用这些入口。

---

## Task 1: Add runtime manager tests

**Step 1: Write failing test**
- File: `src/test/kotlin/cn/kasuminova/astd/renderer/projectile/ASTDProjectileVfxRuntimeManagerTest.kt`
- Required assertions:
  - `track(projectile, preset)` creates one runtime.
  - Tracking the same projectile twice does not duplicate runtime.
  - `advance(engine, amount)` advances tracked runtimes.
  - Projectile removed from engine transitions runtime to fade and then removal.
  - `clear()` disposes all runtimes.

**Step 2: Run test and verify failure**
- Command: `./gradlew test --tests '*ASTDProjectileVfxRuntimeManagerTest'`
- Expected output:
  ```text
  FAILED ASTDProjectileVfxRuntimeManagerTest
  Unresolved reference: ASTDProjectileVfxRuntimeManager
  ```

---

## Task 2: Implement runtime manager

**Step 1: Add manager file**
- File: `src/main/kotlin/cn/kasuminova/astd/renderer/projectile/ASTDProjectileVfxRuntimeManager.kt`
- Required API:
  - `track(engine: CombatEngineAPI, projectile: DamagingProjectileAPI, preset: ASTDProjectileVfxPreset): Boolean`
  - `advance(engine: CombatEngineAPI, amount: Float)`
  - `clear()`
  - `trackedCountForTests(): Int`

Implementation constraints:
- Use projectile identity for dedupe.
- Do not scan all battlefield projectiles.
- Track only explicitly registered projectiles.
- Do not use reflection.
- Do not swallow `Throwable`.
- Do not create vanilla rendering fallback.

**Step 2: Run test and verify success**
- Command: `./gradlew test --tests '*ASTDProjectileVfxRuntimeManagerTest'`
- Expected output:
  ```text
  BUILD SUCCESSFUL
  ```

---

## Task 3: Add runtime preset catalog tests

**Step 1: Write failing test**
- File: `src/test/kotlin/cn/kasuminova/astd/renderer/projectile/ASTDProjectileVfxPresetCatalogTest.kt`
- Required assertions:
  - `ASTDProjectileVfxPresetCatalog.preset("aod7_shot")` returns a preset.
  - Every `preset` id listed in `contents/data/config/astd_projectile_vfx.json` resolves to a runtime preset.
  - Every catalog preset contains at least one `TrailEntity` supported layer.
  - Catalog implementation does not expose preview-only field names.

**Step 2: Run test and verify failure**
- Command: `./gradlew test --tests '*ASTDProjectileVfxPresetCatalogTest'`
- Expected output:
  ```text
  FAILED ASTDProjectileVfxPresetCatalogTest
  Unresolved reference: ASTDProjectileVfxPresetCatalog
  ```

---

## Task 4: Implement runtime preset catalog

**Step 1: Add catalog file**
- File: `src/main/kotlin/cn/kasuminova/astd/renderer/projectile/ASTDProjectileVfxPresetCatalog.kt`
- Required API:
  - `fun preset(id: String): ASTDProjectileVfxPreset?`
  - `fun presetIds(): Set<String>`

Must cover all config preset ids:

- `aod7_shot`
- `spc3_shot`
- `drv9_slug`
- `drv11`
- `drv_omega_slug`
- `slt3_pulse`
- `slt4_burst`
- `slt_omega_stream`
- `vpd6_pulse`
- `vpd_omega_arc`
- `rct6`
- `singularity_event_horizon_missile`
- `tsm_omega_missile`
- `gsp12_rift`
- `jmb2_beam`
- `jmb9_beam`
- `jmb_omega_beam`
- `singularity_nova_missile`
- `fdp4_charge`
- `ftb_omega_beam`
- `mnl2_mine`
- `mnl3_mine`
- `mnl_omega_grid`

Implementation constraints:
- Use only `Trail`, `Glow`, `Ribbon`, `HeadTrail` layers.
- Do not implement Sprite / Flare / instance particle.
- Do not add frontend-only fields.
- Do not use reflection.

**Step 2: Run test and verify success**
- Command: `./gradlew test --tests '*ASTDProjectileVfxPresetCatalogTest'`
- Expected output:
  ```text
  BUILD SUCCESSFUL
  ```

---

## Task 5: Add registry runtime migration tests

**Step 1: Write failing test**
- File: `src/test/kotlin/cn/kasuminova/astd/combat/effect/generic/projectile/ProjectileVfxRegistryRuntimeTest.kt`
- Required assertions:
  - `presetFor(projectileSpecId)` returns `ASTDProjectileVfxPreset?`.
  - Every `projectileSpecId` in `astd_projectile_vfx.json` resolves to a runtime preset.
  - Unconfigured projectile returns `null`.
  - Active registry source no longer references `ProjectileVfxPresets.`.
  - Active registry source no longer exposes `ProjectileSpawnHandler`.

**Step 2: Run test and verify failure**
- Command: `./gradlew test --tests '*ProjectileVfxRegistryRuntimeTest'`
- Expected output:
  ```text
  FAILED ProjectileVfxRegistryRuntimeTest
  Unresolved reference: presetFor
  ```

---

## Task 6: Migrate ProjectileVfxRegistry

**Step 1: Update registry file**
- File: `src/main/kotlin/cn/kasuminova/astd/combat/effect/generic/projectile/ProjectileVfxRegistry.kt`
- Target API:
  - `fun presetFor(projectileSpecId: String): ASTDProjectileVfxPreset?`

Implementation details:
- Read `data/config/astd_projectile_vfx.json`.
- Map `projectileSpecId` to preset id.
- Resolve preset id through `ASTDProjectileVfxPresetCatalog.preset(presetId)`.
- Use a small built-in config fallback based on catalog ids if config cannot load.
- Return `null` for unconfigured projectiles.

Remove from active main path:
- `ProjectileSpawnHandler`
- `PresetHandlers`
- `handlersFor(...)`
- direct calls to `ProjectileVfxPresets`
- direct calls to `CodeProjectileRenderer`

**Step 2: Run test and verify success**
- Command: `./gradlew test --tests '*ProjectileVfxRegistryRuntimeTest'`
- Expected output:
  ```text
  BUILD SUCCESSFUL
  ```

---

## Task 7: Add dispatcher runtime integration tests

**Step 1: Write failing test**
- File: `src/test/kotlin/cn/kasuminova/astd/combat/effect/generic/projectile/ProjectileSpecOnFireDispatcherRuntimeTest.kt`
- Required assertions:
  - Dispatcher keeps missile AI injection path.
  - Configured projectile ids track through `ASTDProjectileVfxRuntimeManager`.
  - Unconfigured projectile ids do not create runtime.
  - Duplicate onFire for the same projectile tracks once.
  - Dispatcher source does not reference `CodeProjectileRenderer` or `ProjectileVfxPresets`.

**Step 2: Run test and verify failure**
- Command: `./gradlew test --tests '*ProjectileSpecOnFireDispatcherRuntimeTest'`
- Expected output:
  ```text
  FAILED ProjectileSpecOnFireDispatcherRuntimeTest
  ```

---

## Task 8: Migrate ProjectileSpecOnFireDispatcher

**Step 1: Update dispatcher file**
- File: `src/main/kotlin/cn/kasuminova/astd/combat/effect/generic/projectile/ProjectileSpecOnFireDispatcher.kt`
- Target behavior:
  - Keep `ProjectileMissileAiInjector.ensureInstalled(engine, projectile)`.
  - Keep onFire dedupe / lock state.
  - Resolve preset through `ProjectileVfxRegistry.presetFor(projId)`.
  - Track via `ASTDProjectileVfxRuntimeManager.track(engine, projectile, preset)`.
  - Mark onFire success only when manager track returns true.

Remove from main path:
- fallback `CodeProjectileRenderer.onSpawn(...)`
- handler loop
- nested debug floating text path
- direct old preset calls

**Step 2: Run test and verify success**
- Command: `./gradlew test --tests '*ProjectileSpecOnFireDispatcherRuntimeTest'`
- Expected output:
  ```text
  BUILD SUCCESSFUL
  ```

---

## Task 9: Add runtime plugin tests

**Step 1: Write failing test**
- File: `src/test/kotlin/cn/kasuminova/astd/renderer/projectile/ASTDProjectileVfxRuntimePluginTest.kt`
- Required assertions:
  - Plugin advance delegates to manager advance.
  - Combat cleanup delegates to manager clear.
  - Plugin source does not scan battlefield projectile lists.

**Step 2: Run test and verify failure**
- Command: `./gradlew test --tests '*ASTDProjectileVfxRuntimePluginTest'`
- Expected output:
  ```text
  FAILED ASTDProjectileVfxRuntimePluginTest
  Unresolved reference: ASTDProjectileVfxRuntimePlugin
  ```

---

## Task 10: Add runtime plugin integration

**Step 1: Add plugin file**
- File: `src/main/kotlin/cn/kasuminova/astd/renderer/projectile/ASTDProjectileVfxRuntimePlugin.kt`
- Required behavior:
  - Extend the appropriate Starsector combat plugin base.
  - `advance(amount, events)` delegates to `ASTDProjectileVfxRuntimeManager.advance(engine, amount)`.
  - Cleanup delegates to `ASTDProjectileVfxRuntimeManager.clear()`.
  - Do not scan all projectiles.

**Step 2: Update mod plugin**
- File: `src/main/java/cn/kasuminova/astd/AsteriaDirectoratePlugin.java`
- Required behavior:
  - Register runtime plugin at combat start or through current combat init path.
  - Do not change campaign initialization behavior.

**Step 3: Run test and verify success**
- Command: `./gradlew test --tests '*ASTDProjectileVfxRuntimePluginTest'`
- Expected output:
  ```text
  BUILD SUCCESSFUL
  ```

---

## Task 11: Add old main path removal tests

**Step 1: Write failing test**
- File: `src/test/kotlin/cn/kasuminova/astd/combat/effect/generic/projectile/ProjectileVfxOldPathRemovalTest.kt`
- Required assertions:
  - Active dispatcher/registry sources do not call:
    - `ProjectileTracerManager.track(`
    - `CodeProjectileRenderer.onSpawn(`
    - `ProjectileVfxPresets.`
    - `CompositeProjectileVisual(`
  - Existing old class definitions may remain temporarily.

**Step 2: Run test and verify failure if old calls remain**
- Command: `./gradlew test --tests '*ProjectileVfxOldPathRemovalTest'`
- Expected output:
  ```text
  FAILED ProjectileVfxOldPathRemovalTest
  ```

---

## Task 12: Remove old main path calls

**Step 1: Update active projectile entry files**
- Files:
  - `src/main/kotlin/cn/kasuminova/astd/combat/effect/generic/projectile/ProjectileVfxRegistry.kt`
  - `src/main/kotlin/cn/kasuminova/astd/combat/effect/generic/projectile/ProjectileSpecOnFireDispatcher.kt`
  - `src/main/kotlin/cn/kasuminova/astd/combat/effect/generic/projectile/ProjectileSpawnVfxDispatcher.kt`

Required behavior:
- No active main path calls to old renderer entry points.
- Old files may remain for later deletion, but entry path points to runtime manager.
- `ProjectileMissileAiInjector` remains active.

**Step 2: Run test and verify success**
- Command: `./gradlew test --tests '*ProjectileVfxOldPathRemovalTest'`
- Expected output:
  ```text
  BUILD SUCCESSFUL
  ```

---

## Task 13: Validate resources and ss-csv output

**Step 1: Verify dispatcher references**
- Command: `grep -RIn 'ProjectileSpecOnFireDispatcher' contents ss-csv/src build/generated/ss-csv || true`
- Expected output:
  ```text
  contents/data/weapons/proj/... ProjectileSpecOnFireDispatcher
  ss-csv/src/... ProjectileSpecOnFireDispatcher
  ```

**Step 2: Run safe ss-csv generation**
- Command: `./gradlew :ss-csv:generateSsCsv`
- Expected output:
  ```text
  BUILD SUCCESSFUL
  ```

**Step 3: Verify generated output package references**
- Command: `grep -RIn 'cn.kasuminova.astd.combat.effect.generic.projectile.ProjectileSpecOnFireDispatcher' build/generated/ss-csv || true`
- Expected output:
  ```text
  build/generated/ss-csv/... ProjectileSpecOnFireDispatcher
  ```

---

## Task 14: Full verification and risk review

**Step 1: Run runtime tests**
- Command: `./gradlew test --tests '*ASTDProjectileVfx*'`
- Expected output:
  ```text
  BUILD SUCCESSFUL
  ```

**Step 2: Run projectile migration tests**
- Command: `./gradlew test --tests '*ProjectileVfx*'`
- Expected output:
  ```text
  BUILD SUCCESSFUL
  ```

**Step 3: Run dispatcher tests**
- Command: `./gradlew test --tests '*ProjectileSpecOnFireDispatcher*'`
- Expected output:
  ```text
  BUILD SUCCESSFUL
  ```

**Step 4: Run full build**
- Command: `./gradlew build`
- Expected output:
  ```text
  BUILD SUCCESSFUL
  ```

**Step 5: Run safe ss-csv generation**
- Command: `./gradlew :ss-csv:generateSsCsv`
- Expected output:
  ```text
  BUILD SUCCESSFUL
  ```

**Step 6: Risk search**
- Command: `grep -RInE 'Class\.forName|forName\(|getDeclared|java\.lang\.reflect|kotlin\.reflect|MethodHandles|ServiceLoader|ClassLoader|timeline|simulation|previewCamera|projectileVelocity|curve|loop|ProjectileTracerManager\.track\(|CodeProjectileRenderer\.onSpawn\(|ProjectileVfxPresets\.|CompositeProjectileVisual\(|catch\s*\([^)]*(Throwable|Exception|any|unknown|e)\)|catch\s*\{' src/main/kotlin/cn/kasuminova/astd/renderer/projectile src/main/kotlin/cn/kasuminova/astd/combat/effect/generic/projectile src/test/kotlin/cn/kasuminova/astd/renderer/projectile src/test/kotlin/cn/kasuminova/astd/combat/effect/generic/projectile || true`
- Expected output:
  ```text
  ```
  Test forbidden-string lists may be reported separately and classified as test assertions.

---

## Acceptance Criteria

- Projectile VFX main path is unified as:
  - `.proj onFireEffect`
  - `ProjectileSpecOnFireDispatcher`
  - `ProjectileVfxRegistry.presetFor`
  - `ASTDProjectileVfxRuntimeManager.track`
  - `ASTDProjectileVfxRuntimePlugin.advance`
  - `ASTDProjectileVfxRuntime`
  - BoxUtil `TrailEntity`
- Every preset in `contents/data/config/astd_projectile_vfx.json` resolves through `ASTDProjectileVfxPresetCatalog`.
- Unconfigured projectile creates no runtime.
- Same projectile cannot create duplicate runtimes.
- Missile/tracking projectiles use real projectile history.
- Old projectile VFX main path is no longer called.
- Preview-only fields do not enter main runtime.
- Production code contains no reflection / dynamic class lookup.
- No vanilla rendering fallback is added.
- No try-catch storm is added.
- `./gradlew build` succeeds.
- `./gradlew :ss-csv:generateSsCsv` succeeds.
