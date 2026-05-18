# ASTD Round 3 Projectile VFX Export Boundary Plan

**Goal:** 修正前端 projectile VFX preview 的导出边界，建立“预览专用配置”和“游戏内导出配置”的明确分层，为后续 BoxUtil 游戏内 runtime 提供干净数据模型。

**Architecture:** 前端保留完整预览能力；导出层只输出游戏 runtime 需要的 VFX preset 数据；`timeline`、`simulation`、`previewCamera` 等字段继续服务预览 UI，但不进入游戏导出结果。

**Tech Stack:** TypeScript, React, Vite, Vitest, Kotlin export text generation

---

## Scope

本轮执行：

1. 拆分 Preview Model 与 Game Export Model。
2. 新增游戏导出类型与校验策略。
3. 修改 Kotlin 导出逻辑，只输出游戏字段。
4. UI 中清晰标注 Preview Only 与 Game Export 配置。
5. 添加测试覆盖导出边界。
6. 保留前端预览现有行为。

本轮范围外：

- 游戏内 BoxUtil runtime 实现
- 旧射弹渲染体系替换
- ss-csv 项目重构
- TrailEntity 实际生成器接入游戏
- Sprite/Flare/实例粒子功能实现

---

## Field boundary

### Preview Only

These fields are frontend-only preview controls:

- `timeline`
- `simulation`
- `previewCamera`
- `projectileVelocity`
- `curve`
- `loop`
- frontend-only debug fields
- canvas camera, zoom, and playback controls

### Game Export

These fields may be exported to game runtime:

- preset id/name
- trail entity layers
- base color / width / length / opacity
- ribbon decoration visual parameters
- jitter/noise visual parameters
- material/blend-like semantic fields
- projectile hook id or renderer hook id
- lifecycle fade parameters
- sample policy parameters for non-linear projectile history rendering

---

## Task 1: Add game export boundary tests

**Step 1: Write failing test**
- File: `tools/projectile-vfx-preview/src/export/gameExport.test.ts`
- Code:
  ```typescript
  import { describe, expect, it } from 'vitest';
  import { defaultPreset } from '../model/preset';
  import { serializeGameExportPreset, toGameExportPreset } from './gameExport';

  describe('game projectile vfx export boundary', () => {
    it('omits preview-only fields from the game export preset', () => {
      const exported = toGameExportPreset(defaultPreset);
      const json = JSON.stringify(exported);

      expect(exported).toHaveProperty('name');
      expect(exported).toHaveProperty('trailEntities');
      expect(exported).toHaveProperty('hooks');
      expect(exported).toHaveProperty('lifecycle');

      expect(json).not.toContain('timeline');
      expect(json).not.toContain('simulation');
      expect(json).not.toContain('previewCamera');
      expect(json).not.toContain('projectileVelocity');
      expect(json).not.toContain('curve');
      expect(json).not.toContain('loop');
    });

    it('serializes game export presets deterministically', () => {
      expect(serializeGameExportPreset(defaultPreset)).toBe(serializeGameExportPreset(defaultPreset));
    });
  });
  ```

**Step 2: Run test and verify failure**
- Command: `cd tools/projectile-vfx-preview && npm run test:run -- src/export/gameExport.test.ts`
- Expected output:
  ```text
  FAIL src/export/gameExport.test.ts
  Cannot find module './gameExport'
  ```

---

## Task 2: Add game export model types

**Step 1: Create game export model file**
- File: `tools/projectile-vfx-preview/src/model/gameExport.ts`
- Code:
  ```typescript
  import type { Rgba } from './preset';

  export interface GameProjectileVfxPreset {
    name: string;
    trailEntities: GameTrailEntityConfig[];
    hooks: GameProjectileVfxHookConfig[];
    lifecycle: GameProjectileVfxLifecycleConfig;
    samplePolicy: GameProjectileSamplePolicy;
  }

  export interface GameTrailEntityConfig {
    id: string;
    enabled: boolean;
    width: number;
    length: number;
    opacity: number;
    color: Rgba;
    blendMode: 'normal' | 'additive' | 'screen';
    ribbonDecorations: GameTrailRibbonDecorationConfig[];
  }

  export interface GameTrailRibbonDecorationConfig {
    id: string;
    enabled: boolean;
    color: Rgba;
    width: number;
    opacity: number;
    phaseOffset: number;
    frequency: number;
    amplitude: number;
  }

  export interface GameProjectileVfxHookConfig {
    id: string;
    kind: 'onFire' | 'onAdvance' | 'onHit' | 'onExpire';
  }

  export interface GameProjectileVfxLifecycleConfig {
    fadeInSeconds: number;
    fadeOutSeconds: number;
  }

  export interface GameProjectileSamplePolicy {
    mode: 'projectile-history';
    maxSamples: number;
    minSampleDistance: number;
  }
  ```

**Step 2: Run test and verify failure still targets missing converter**
- Command: `cd tools/projectile-vfx-preview && npm run test:run -- src/export/gameExport.test.ts`
- Expected output:
  ```text
  FAIL src/export/gameExport.test.ts
  Cannot find module './gameExport'
  ```

---

## Task 3: Implement game export converter

**Step 1: Create converter file**
- File: `tools/projectile-vfx-preview/src/export/gameExport.ts`
- Code:
  ```typescript
  import type { GameProjectileVfxPreset, GameTrailEntityConfig } from '../model/gameExport';
  import type { BoxUtilPreviewPreset, TrailEntityConfig } from '../model/preset';

  export function toGameExportPreset(preset: BoxUtilPreviewPreset): GameProjectileVfxPreset {
    return {
      name: preset.name,
      trailEntities: preset.trailEntities.map(toGameTrailEntityConfig),
      hooks: [],
      lifecycle: {
        fadeInSeconds: 0,
        fadeOutSeconds: 0.15,
      },
      samplePolicy: {
        mode: 'projectile-history',
        maxSamples: 96,
        minSampleDistance: 2,
      },
    };
  }

  export function serializeGameExportPreset(preset: BoxUtilPreviewPreset): string {
    return JSON.stringify(toGameExportPreset(preset), null, 2);
  }

  function toGameTrailEntityConfig(entity: TrailEntityConfig): GameTrailEntityConfig {
    return {
      id: entity.id,
      enabled: entity.enabled,
      width: entity.width,
      length: entity.length,
      opacity: entity.opacity,
      color: entity.color,
      blendMode: entity.blendMode,
      ribbonDecorations: entity.ribbonDecorations.map((decoration) => ({
        id: decoration.id,
        enabled: decoration.enabled,
        color: decoration.color,
        width: decoration.width,
        opacity: decoration.opacity,
        phaseOffset: decoration.phaseOffset,
        frequency: decoration.frequency,
        amplitude: decoration.amplitude,
      })),
    };
  }
  ```

**Step 2: Run test and verify success**
- Command: `cd tools/projectile-vfx-preview && npm run test:run -- src/export/gameExport.test.ts`
- Expected output:
  ```text
  PASS src/export/gameExport.test.ts
  ```

---

## Task 4: Tighten Kotlin export tests

**Step 1: Add failing assertions**
- File: `tools/projectile-vfx-preview/src/export/kotlinExport.test.ts`
- Add assertions to the existing Kotlin export test:
  ```typescript
  expect(kotlinText).not.toContain('TimelineConfig');
  expect(kotlinText).not.toContain('SimulationConfig');
  expect(kotlinText).not.toContain('PreviewCameraConfig');
  expect(kotlinText).not.toContain('projectileVelocity');
  expect(kotlinText).not.toContain('curve');
  expect(kotlinText).not.toContain('loop');

  expect(kotlinText).toContain('ASTDProjectileVfxPreset');
  expect(kotlinText).toContain('ASTDTrailEntitySpec');
  expect(kotlinText).toContain('ASTDTrailLayerSpec');
  expect(kotlinText).toContain('ASTDTrailRibbonDecorationSpec');
  ```

**Step 2: Run test and verify failure**
- Command: `cd tools/projectile-vfx-preview && npm run test:run -- src/export/kotlinExport.test.ts`
- Expected output:
  ```text
  FAIL src/export/kotlinExport.test.ts
  ```

---

## Task 5: Rework Kotlin export entry point

**Step 1: Update Kotlin exporter**
- File: `tools/projectile-vfx-preview/src/export/kotlinExport.ts`
- Required behavior:
  - Import `toGameExportPreset`.
  - Build Kotlin text from `GameProjectileVfxPreset`.
  - Emit game runtime names:
    - `ASTDProjectileVfxPreset`
    - `ASTDTrailEntitySpec`
    - `ASTDTrailLayerSpec`
    - `ASTDTrailRibbonDecorationSpec`
    - `ASTDProjectileVfxHookSpec`
    - `ASTDProjectileLifecycleSpec`
  - Avoid all Preview Only fields.

**Step 2: Run Kotlin export test and verify success**
- Command: `cd tools/projectile-vfx-preview && npm run test:run -- src/export/kotlinExport.test.ts`
- Expected output:
  ```text
  PASS src/export/kotlinExport.test.ts
  ```

---

## Task 6: Add UI boundary labels

**Step 1: Write UI test**
- File: `tools/projectile-vfx-preview/src/ui/PresetEditor.test.tsx`
- Add assertions:
  ```typescript
  expect(screen.getByText(/Preview Only/i)).toBeTruthy();
  expect(screen.getByText(/Game Export/i)).toBeTruthy();
  expect(screen.getByText(/export.*Game Export/i)).toBeTruthy();
  ```

**Step 2: Run test and verify failure**
- Command: `cd tools/projectile-vfx-preview && npm run test:run -- src/ui/PresetEditor.test.tsx`
- Expected output:
  ```text
  FAIL src/ui/PresetEditor.test.tsx
  ```

**Step 3: Update editor UI**
- File: `tools/projectile-vfx-preview/src/ui/PresetEditor.tsx`
- Required behavior:
  - Mark timeline, simulation, and camera controls as `Preview Only`.
  - Mark trail entity, ribbon, color, and material controls as `Game Export`.
  - Add export boundary copy near export controls: `Export includes Game Export settings only.`

**Step 4: Run UI test and verify success**
- Command: `cd tools/projectile-vfx-preview && npm run test:run -- src/ui/PresetEditor.test.tsx`
- Expected output:
  ```text
  PASS src/ui/PresetEditor.test.tsx
  ```

---

## Task 7: Preserve preview renderer behavior

**Step 1: Keep renderer regression assertions**
- File: `tools/projectile-vfx-preview/src/render/webglTrailRenderer.test.ts`
- Required assertions:
  - overlay renderer still accepts the full preview preset.
  - ribbon path rendering remains continuous.
  - ribbon composite remains `source-over`.

**Step 2: Run renderer tests**
- Command: `cd tools/projectile-vfx-preview && npm run test:run -- src/render/webglTrailRenderer.test.ts`
- Expected output:
  ```text
  PASS src/render/webglTrailRenderer.test.ts
  ```

---

## Task 8: Full frontend verification

**Step 1: Run full test suite**
- Command: `cd tools/projectile-vfx-preview && npm run test:run`
- Expected output:
  ```text
  Test Files  ... passed
  Tests       ... passed
  ```

**Step 2: Run frontend build**
- Command: `cd tools/projectile-vfx-preview && npm run build`
- Expected output:
  ```text
  built in ...
  ```

---

## Acceptance Criteria

- Frontend preview preset keeps timeline, simulation, and camera support.
- Game export model contains only runtime-oriented VFX data.
- Kotlin export text excludes all Preview Only fields.
- UI clearly distinguishes `Preview Only` from `Game Export`.
- `cd tools/projectile-vfx-preview && npm run test:run` succeeds.
- `cd tools/projectile-vfx-preview && npm run build` succeeds.
- Game runtime implementation remains reserved for the following round.
