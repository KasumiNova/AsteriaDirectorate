# ASTD Projectile VFX Preview Implementation Plan

**Goal:** 创建一个独立的 React + WebGL 网页工具，用于按 BoxUtil API 语义预览弹体拖尾、逐帧观察、调参和导出 JSON。

**Architecture:** Vite React 单页应用；核心分为 preset schema、BoxUtil 风格模拟层、WebGL 渲染层、React UI 控制层、导入导出层。

**Tech Stack:** TypeScript, Vite, React, Vitest, WebGL Canvas API, plain CSS。

---

## Requirements Source

- Design doc: `.agents/superpower/brainstorm/2026-05-07-projectile-vfx-preview-design.md`
- Target directory: `tools/projectile-vfx-preview/`
- Repository constraints:
  - Keep BoxUtil as read-only reference.
  - Place generated app sources under ASTD `tools/`.
  - Do not modify `build/**`.
  - This tool is independent from Starsector runtime and game combat smoke tests.

---

## Task 1: Create Vite React tool skeleton

**Step 1: Write project files**

Create these files:

- `tools/projectile-vfx-preview/package.json`
- `tools/projectile-vfx-preview/index.html`
- `tools/projectile-vfx-preview/tsconfig.json`
- `tools/projectile-vfx-preview/tsconfig.node.json`
- `tools/projectile-vfx-preview/vite.config.ts`
- `tools/projectile-vfx-preview/src/main.tsx`
- `tools/projectile-vfx-preview/src/App.tsx`
- `tools/projectile-vfx-preview/src/App.css`
- `tools/projectile-vfx-preview/src/test/setup.ts`

`package.json` must include scripts:

- `dev`: `vite`
- `build`: `tsc && vite build`
- `test`: `vitest`
- `test:run`: `vitest run`

Dependencies:

- runtime: `@vitejs/plugin-react`, `vite`, `typescript`, `react`, `react-dom`
- test/dev: `vitest`, `jsdom`, `@testing-library/react`, `@testing-library/jest-dom`, `@testing-library/user-event`

**Step 2: Run build and expect dependency error until npm install**

Command:

```bash
cd /mnt/windows_data/Games/Starsector098-linux/mods/Asteria_Directorate/tools/projectile-vfx-preview && npm run build
```

Expected before install:

- npm reports missing packages or missing `node_modules`.

**Step 3: Install dependencies**

Command:

```bash
cd /mnt/windows_data/Games/Starsector098-linux/mods/Asteria_Directorate/tools/projectile-vfx-preview && npm install
```

Expected:

- `package-lock.json` created.
- Command exits with status 0.

**Step 4: Run build**

Command:

```bash
cd /mnt/windows_data/Games/Starsector098-linux/mods/Asteria_Directorate/tools/projectile-vfx-preview && npm run build
```

Expected:

- TypeScript compiles.
- Vite outputs `dist/`.

---

## Task 2: Implement BoxUtil preview preset model using TDD

**Step 1: Write failing test**

File: `tools/projectile-vfx-preview/src/model/preset.test.ts`

Test coverage:

- `createDefaultPreset()` returns `trailEntities`, `spriteEntities`, `segmentEntities` arrays.
- Default trail contains `startWidth`, `endWidth`, `texturePixels`, `textureSpeed`, `uvOffset`.
- Default trail contains `startColor`, `endColor`, `startEmissive`, `endEmissive`.
- Default timeline uses 60 FPS.

**Step 2: Run test and verify failure**

Command:

```bash
cd /mnt/windows_data/Games/Starsector098-linux/mods/Asteria_Directorate/tools/projectile-vfx-preview && npm run test:run
```

Expected:

- Test fails because `src/model/preset.ts` does not exist.

**Step 3: Implement model**

File: `tools/projectile-vfx-preview/src/model/preset.ts`

Required exports:

- `Vec2`
- `Rgba`
- `TrailNode`
- `TrailEntityConfig`
- `SpriteEntityConfig`
- `SegmentNodeConfig`
- `SegmentEntityConfig`
- `TimelineConfig`
- `PreviewCameraConfig`
- `SimulationConfig`
- `BoxUtilPreviewPreset`
- `createDefaultPreset()`

Required `TrailEntityConfig` fields:

- `id`
- `nodes`
- `startColor`
- `endColor`
- `startEmissive`
- `endEmissive`
- `startWidth`
- `endWidth`
- `texturePixels`
- `textureSpeed`
- `uvOffset`
- `fillStartAlpha`
- `fillEndAlpha`
- `fillStartFactor`
- `fillEndFactor`
- `jitterPower`
- `flick`
- `syncFlick`
- `stripLineMode`
- `flowWhenPaused`
- `flickWhenPaused`
- `flickMixValue`
- `flickerSyncCode`
- `blendMode`

Required `blendMode` values: `normal` and `additive`.

**Step 4: Run test and verify success**

Command:

```bash
cd /mnt/windows_data/Games/Starsector098-linux/mods/Asteria_Directorate/tools/projectile-vfx-preview && npm run test:run
```

Expected:

- `preset.test.ts` passes.

---

## Task 3: Implement JSON parsing and validation using TDD

**Step 1: Write failing test**

File: `tools/projectile-vfx-preview/src/model/parsePreset.test.ts`

Test coverage:

- Valid JSON returns `{ ok: true, preset }`.
- Malformed JSON returns `{ ok: false, errors }`.
- Missing optional arrays are defaulted to empty arrays.
- Invalid color arrays produce field path errors.
- Invalid trail widths produce field path errors.

**Step 2: Run test and verify failure**

Command:

```bash
cd /mnt/windows_data/Games/Starsector098-linux/mods/Asteria_Directorate/tools/projectile-vfx-preview && npm run test:run
```

Expected:

- Test fails because parser does not exist.

**Step 3: Implement parser**

File: `tools/projectile-vfx-preview/src/model/parsePreset.ts`

Required exports:

- `ParsePresetError`
- `ParsePresetResult`
- `parsePresetJson(input: string): ParsePresetResult`
- `formatPresetJson(preset: BoxUtilPreviewPreset): string`

Validation requirements:

- Accept only JSON object root.
- Validate `trailEntities` as array.
- Validate RGBA as four finite numbers.
- Validate vec2 as two finite numbers or object `{ x, y }` consistently by chosen model.
- Validate node positions.
- Validate width and texture fields as finite numbers.
- Report errors with paths such as `trailEntities[0].startWidth`.

**Step 4: Run tests and verify success**

Command:

```bash
cd /mnt/windows_data/Games/Starsector098-linux/mods/Asteria_Directorate/tools/projectile-vfx-preview && npm run test:run
```

Expected:

- Parser and preset tests pass.

---

## Task 4: Implement TrailEntity-style geometry using TDD

**Step 1: Write failing test**

File: `tools/projectile-vfx-preview/src/render/trailGeometry.test.ts`

Test coverage:

- Two nodes produce one quad with six vertices.
- Three nodes produce two quads with twelve vertices.
- Vertices include position, UV, color, emissive, alpha.
- Cumulative distance affects the V coordinate.
- `startWidth` and `endWidth` affect lateral offsets.
- `fillStartAlpha` and `fillEndAlpha` affect alpha taper.

**Step 2: Run test and verify failure**

Command:

```bash
cd /mnt/windows_data/Games/Starsector098-linux/mods/Asteria_Directorate/tools/projectile-vfx-preview && npm run test:run
```

Expected:

- Test fails because geometry module does not exist.

**Step 3: Implement geometry**

File: `tools/projectile-vfx-preview/src/render/trailGeometry.ts`

Required exports:

- `TrailVertex`
- `TrailMesh`
- `buildTrailMesh(config: TrailEntityConfig, timeSeconds: number): TrailMesh`
- `computeTrailDistances(nodes: TrailNode[]): number[]`

Geometry rules:

- Node order follows BoxUtil `TrailEntity` semantics.
- Each adjacent node pair generates one quad.
- Quad is emitted as two triangles.
- Width interpolates along total distance.
- Color/emissive interpolate along total distance.
- UV uses `distance / texturePixels + uvOffset + timeSeconds * textureSpeed`.
- Taper uses `fillStartAlpha`, `fillEndAlpha`, `fillStartFactor`, `fillEndFactor`.
- `jitterPower` is stored for shader simulation; deterministic CPU fallback may use a seeded sine noise.

**Step 4: Run tests and verify success**

Command:

```bash
cd /mnt/windows_data/Games/Starsector098-linux/mods/Asteria_Directorate/tools/projectile-vfx-preview && npm run test:run
```

Expected:

- Trail geometry tests pass.

---

## Task 5: Implement WebGL TrailEntity preview renderer using TDD

**Step 1: Write failing test**

File: `tools/projectile-vfx-preview/src/render/webglTrailRenderer.test.ts`

Test coverage:

- Creating renderer with null context returns an unavailable state.
- Shader source includes uniforms/attributes for time, jitter, flick, color, emissive, UV.
- Renderer accepts a mesh and updates draw stats.

**Step 2: Run test and verify failure**

Command:

```bash
cd /mnt/windows_data/Games/Starsector098-linux/mods/Asteria_Directorate/tools/projectile-vfx-preview && npm run test:run
```

Expected:

- Test fails because renderer module does not exist.

**Step 3: Implement renderer**

File: `tools/projectile-vfx-preview/src/render/webglTrailRenderer.ts`

Required exports:

- `TRAIL_VERTEX_SHADER_SOURCE`
- `TRAIL_FRAGMENT_SHADER_SOURCE`
- `WebGLTrailRenderer`
- `createWebGLTrailRenderer(canvas: HTMLCanvasElement): WebGLTrailRenderer | null`

Renderer requirements:

- Compile shaders.
- Upload mesh vertex buffer.
- Draw triangles.
- Support normal/additive blending.
- Approximate emissive add.
- Approximate `jitterPower` in fragment shader.
- Approximate `flick`/`syncFlick` using deterministic sine noise and `flickerSyncCode`.
- Expose `getStats()` with draw calls and vertex count.

**Step 4: Run tests and verify success**

Command:

```bash
cd /mnt/windows_data/Games/Starsector098-linux/mods/Asteria_Directorate/tools/projectile-vfx-preview && npm run test:run
```

Expected:

- WebGL renderer tests pass.

---

## Task 6: Implement PreviewCanvas component using TDD

**Step 1: Write failing test**

File: `tools/projectile-vfx-preview/src/ui/PreviewCanvas.test.tsx`

Test coverage:

- Component renders `<canvas>`.
- Canvas has `aria-label="Projectile VFX preview canvas"`.
- Component accepts `preset` and `timeSeconds` props.
- Component displays fallback text when WebGL is unavailable.

**Step 2: Run test and verify failure**

Command:

```bash
cd /mnt/windows_data/Games/Starsector098-linux/mods/Asteria_Directorate/tools/projectile-vfx-preview && npm run test:run
```

Expected:

- Test fails because component does not exist.

**Step 3: Implement component**

File: `tools/projectile-vfx-preview/src/ui/PreviewCanvas.tsx`

Requirements:

- Create canvas ref.
- Initialize renderer in `useEffect`.
- Resize canvas to element size.
- Render all `trailEntities` from preset.
- Clear background each render.
- Keep renderer lifecycle simple and deterministic.

**Step 4: Run tests and verify success**

Command:

```bash
cd /mnt/windows_data/Games/Starsector098-linux/mods/Asteria_Directorate/tools/projectile-vfx-preview && npm run test:run
```

Expected:

- Preview canvas test passes.

---

## Task 7: Implement timeline controls using TDD

**Step 1: Write failing test**

File: `tools/projectile-vfx-preview/src/sim/timeline.test.ts`

Test coverage:

- `play` sets playing state.
- `pause` clears playing state.
- `stepForward` advances one frame.
- `stepBackward` rewinds one frame.
- `seek` clamps to duration.
- FPS defaults to 60.

**Step 2: Run test and verify failure**

Command:

```bash
cd /mnt/windows_data/Games/Starsector098-linux/mods/Asteria_Directorate/tools/projectile-vfx-preview && npm run test:run
```

Expected:

- Test fails because timeline module does not exist.

**Step 3: Implement reducer**

File: `tools/projectile-vfx-preview/src/sim/timeline.ts`

Required exports:

- `TimelineState`
- `TimelineAction`
- `createInitialTimelineState(config?: TimelineConfig): TimelineState`
- `timelineReducer(state, action): TimelineState`
- `getCurrentFrame(state): number`

**Step 4: Implement component**

File: `tools/projectile-vfx-preview/src/ui/TimelineControls.tsx`

Requirements:

- Play/pause button.
- Step backward/forward buttons.
- Range time slider.
- Current frame and time display.

**Step 5: Run tests and verify success**

Command:

```bash
cd /mnt/windows_data/Games/Starsector098-linux/mods/Asteria_Directorate/tools/projectile-vfx-preview && npm run test:run
```

Expected:

- Timeline tests pass.

---

## Task 8: Implement configuration panel using TDD

**Step 1: Write failing test**

File: `tools/projectile-vfx-preview/src/ui/ConfigPanel.test.tsx`

Test coverage:

- Textarea accepts JSON.
- Apply parses JSON.
- Invalid JSON shows errors.
- Valid JSON calls `onPresetChange`.
- Export button outputs formatted JSON.

**Step 2: Run test and verify failure**

Command:

```bash
cd /mnt/windows_data/Games/Starsector098-linux/mods/Asteria_Directorate/tools/projectile-vfx-preview && npm run test:run
```

Expected:

- Test fails because component does not exist.

**Step 3: Implement component**

File: `tools/projectile-vfx-preview/src/ui/ConfigPanel.tsx`

Requirements:

- Collapsible drawer.
- JSON textarea.
- Apply button.
- Export JSON button.
- Error list.
- Success status.

**Step 4: Run tests and verify success**

Command:

```bash
cd /mnt/windows_data/Games/Starsector098-linux/mods/Asteria_Directorate/tools/projectile-vfx-preview && npm run test:run
```

Expected:

- Config panel tests pass.

---

## Task 9: Implement BoxUtil entity inspector using TDD

**Step 1: Write failing test**

File: `tools/projectile-vfx-preview/src/ui/EntityInspector.test.tsx`

Test coverage:

- Displays `TrailEntity` group.
- Edits `startWidth` and `endWidth`.
- Edits `texturePixels`, `textureSpeed`, `uvOffset`.
- Edits `jitterPower` and `flickMixValue`.
- Calls `onPresetChange` with updated preset.

**Step 2: Run test and verify failure**

Command:

```bash
cd /mnt/windows_data/Games/Starsector098-linux/mods/Asteria_Directorate/tools/projectile-vfx-preview && npm run test:run
```

Expected:

- Test fails because inspector does not exist.

**Step 3: Implement inspector**

File: `tools/projectile-vfx-preview/src/ui/EntityInspector.tsx`

Requirements:

- Right side collapsible panel.
- TrailEntity controls.
- Numeric inputs.
- Boolean checkboxes.
- Color RGBA numeric inputs.
- Immutable preset updates.

**Step 4: Run tests and verify success**

Command:

```bash
cd /mnt/windows_data/Games/Starsector098-linux/mods/Asteria_Directorate/tools/projectile-vfx-preview && npm run test:run
```

Expected:

- Entity inspector tests pass.

---

## Task 10: Integrate App layout using TDD

**Step 1: Write failing integration test**

File: `tools/projectile-vfx-preview/src/App.test.tsx`

Test coverage:

- Page contains main canvas.
- Page contains config drawer entry.
- Page contains timeline controls.
- Page contains entity inspector.
- Default preset renders its name.

**Step 2: Run test and verify failure**

Command:

```bash
cd /mnt/windows_data/Games/Starsector098-linux/mods/Asteria_Directorate/tools/projectile-vfx-preview && npm run test:run
```

Expected:

- Integration test fails until layout is wired.

**Step 3: Implement App**

File: `tools/projectile-vfx-preview/src/App.tsx`

Requirements:

- Own preset state.
- Own timeline state.
- Use animation frame while playing.
- Render preview-first layout.
- Wire config panel, inspector and timeline.

**Step 4: Implement final styles**

File: `tools/projectile-vfx-preview/src/App.css`

Requirements:

- Dark theme.
- Preview-first large canvas area.
- Right collapsible inspector.
- Left top config drawer.
- Bottom fixed timeline controls.
- Responsive minimum layout.

**Step 5: Run tests and verify success**

Command:

```bash
cd /mnt/windows_data/Games/Starsector098-linux/mods/Asteria_Directorate/tools/projectile-vfx-preview && npm run test:run
```

Expected:

- All current tests pass.

---

## Task 11: Implement screenshot export and version comparison using TDD

**Step 1: Write failing tests**

Files:

- `tools/projectile-vfx-preview/src/ui/capture.test.ts`
- `tools/projectile-vfx-preview/src/ui/VersionCompare.test.tsx`

Test coverage:

- `captureCanvasPng(canvas)` returns a PNG data URL.
- Saving a version stores a named preset snapshot.
- Selecting a saved version restores it.

**Step 2: Run test and verify failure**

Command:

```bash
cd /mnt/windows_data/Games/Starsector098-linux/mods/Asteria_Directorate/tools/projectile-vfx-preview && npm run test:run
```

Expected:

- Tests fail because modules do not exist.

**Step 3: Implement capture helper**

File: `tools/projectile-vfx-preview/src/ui/capture.ts`

**Step 4: Implement version compare component**

File: `tools/projectile-vfx-preview/src/ui/VersionCompare.tsx`

**Step 5: Wire to App**

File: `tools/projectile-vfx-preview/src/App.tsx`

**Step 6: Run tests and verify success**

Command:

```bash
cd /mnt/windows_data/Games/Starsector098-linux/mods/Asteria_Directorate/tools/projectile-vfx-preview && npm run test:run
```

Expected:

- All tests pass.

---

## Task 12: Add example preset and README

**Step 1: Add example JSON**

File: `tools/projectile-vfx-preview/examples/basic-trail.json`

Requirements:

- Visible trail.
- Strong color contrast.
- Non-zero `textureSpeed`.
- Different start/end widths.
- Non-zero `jitterPower`.

**Step 2: Add README**

File: `tools/projectile-vfx-preview/README.md`

Required sections:

- Purpose.
- Install.
- Development command.
- Build command.
- Test command.
- Input format.
- BoxUtil API field mapping.
- Export and ASTD回填 workflow.

**Step 3: Run build**

Command:

```bash
cd /mnt/windows_data/Games/Starsector098-linux/mods/Asteria_Directorate/tools/projectile-vfx-preview && npm run build
```

Expected:

- Build succeeds.

---

## Task 13: Final verification

**Step 1: Run tests**

Command:

```bash
cd /mnt/windows_data/Games/Starsector098-linux/mods/Asteria_Directorate/tools/projectile-vfx-preview && npm run test:run
```

Expected:

- All tests pass.

**Step 2: Run build**

Command:

```bash
cd /mnt/windows_data/Games/Starsector098-linux/mods/Asteria_Directorate/tools/projectile-vfx-preview && npm run build
```

Expected:

- TypeScript and Vite build pass.
- `dist/` is generated.

**Step 3: Start dev server**

Command:

```bash
cd /mnt/windows_data/Games/Starsector098-linux/mods/Asteria_Directorate/tools/projectile-vfx-preview && npm run dev -- --host 127.0.0.1
```

Expected:

- Vite prints local dev server URL.
- App loads with preview canvas, config drawer, timeline, inspector, version compare and screenshot controls.

**Step 4: Manual acceptance checks**

- Default preset renders visible trail.
- Changing `textureSpeed` changes UV flow.
- Changing `texturePixels` changes texture scale.
- Changing `uvOffset` shifts the trail texture.
- Changing widths changes visible trail thickness.
- Changing colors/emissive changes output color and glow approximation.
- Exported JSON preserves BoxUtil API style fields.

---

## Handoff

After saving this plan, execute it with the implementation agent. The implementation agent should report:

- Files created/changed.
- Test command results.
- Build command results.
- Any deviations from the plan.
