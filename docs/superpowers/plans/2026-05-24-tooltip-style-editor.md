# Tooltip Style Editor Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a new `tools/tooltip-style-editor` web tool that renders and edits Starsector-style Hullmod tooltips, including a WebGL shader background editor and full live preview.

**Architecture:** Create an independent Vite + React + TypeScript tool that mirrors the project shape of `tools/projectile-vfx-preview` while keeping a separate tooltip-focused model, renderer, and export pipeline. The preview uses a WebGL fullscreen shader layer for game-realizable background effects and a deterministic tooltip layout model for text, highlights, headings, and Hullmod metadata. Export code emits stable JSON and Kotlin-oriented scaffold text that can later map to `TooltipMakerAPI`, `BaseCustomUIPanelPlugin`, and the existing ASTD tooltip DSL.

**Tech Stack:** Vite, React, TypeScript, Vitest, Testing Library, WebGL 1.0 / GLSL ES 1.00, Material Design 3 CSS tokens.

---

## Existing Context

- Existing reference tool: `tools/projectile-vfx-preview`.
- Existing web tech stack: Vite + React + TypeScript + Vitest.
- Existing game tooltip path:
  - `src/main/kotlin/cn/kasuminova/astd/ui/dsl/TooltipDsl.kt`
  - `src/main/kotlin/cn/kasuminova/astd/ui/effect/ASTDParticleBackground.kt`
  - `src/main/kotlin/cn/kasuminova/astd/combat/hullmods/arc/ASTDArcFlareHullModTooltip.kt`
- Existing VFX preview WebGL renderer:
  - `tools/projectile-vfx-preview/src/render/webglTrailRenderer.ts`
  - `tools/projectile-vfx-preview/src/ui/PreviewCanvas.tsx`
- Do not modify unrelated untracked files already present in the worktree.

## File Structure

Create these files under `tools/tooltip-style-editor/`:

- `package.json`: scripts and dependencies aligned with `projectile-vfx-preview`.
- `index.html`, `vite.config.ts`, `tsconfig.json`, `tsconfig.node.json`, `src/vite-env.d.ts`: Vite project plumbing.
- `src/main.tsx`: React bootstrap.
- `src/App.tsx`: top-level editor state and layout.
- `src/App.css`: Material Design 3 inspired app styling.
- `src/model/tooltipPreset.ts`: tooltip preset types, validation, merge helpers.
- `src/model/defaultHullmodPreset.ts`: screenshot-inspired default Hullmod tooltip preset.
- `src/model/tooltipPreset.test.ts`: model tests.
- `src/render/tooltipLayout.ts`: deterministic layout helpers for line wrapping and block sizing.
- `src/render/tooltipLayout.test.ts`: layout tests.
- `src/render/webgl/shaderCompiler.ts`: WebGL shader compile/link helper with structured error results.
- `src/render/webgl/fullscreenShaderRenderer.ts`: fullscreen fragment-shader renderer.
- `src/render/webgl/shaderCompiler.test.ts`: unit tests with injected fake WebGL context.
- `src/ui/TooltipPreview.tsx`: full tooltip preview.
- `src/ui/TooltipPreview.test.tsx`: preview behavior tests.
- `src/ui/BlockEditor.tsx`: text/heading/highlight editor.
- `src/ui/ThemeEditor.tsx`: colors, dimensions, typography controls.
- `src/ui/ShaderEditor.tsx`: GLSL source and uniform controls.
- `src/export/gameTooltipExport.ts`: JSON export.
- `src/export/kotlinTooltipExport.ts`: Kotlin scaffold export.
- `src/export/gameTooltipExport.test.ts`: export tests.

Keep the first implementation scoped to the web tool. Do not implement the in-game Kotlin renderer in this plan; only make the output format suitable for that later step.

## Task 1: Project Skeleton, Preset Model, and Default Hullmod Tooltip

**Files:**
- Create: `tools/tooltip-style-editor/package.json`
- Create: `tools/tooltip-style-editor/index.html`
- Create: `tools/tooltip-style-editor/vite.config.ts`
- Create: `tools/tooltip-style-editor/tsconfig.json`
- Create: `tools/tooltip-style-editor/tsconfig.node.json`
- Create: `tools/tooltip-style-editor/src/vite-env.d.ts`
- Create: `tools/tooltip-style-editor/src/main.tsx`
- Create: `tools/tooltip-style-editor/src/model/tooltipPreset.ts`
- Create: `tools/tooltip-style-editor/src/model/defaultHullmodPreset.ts`
- Create: `tools/tooltip-style-editor/src/model/tooltipPreset.test.ts`

- [ ] **Step 1: Write model tests first**

Create tests covering:

```ts
import { describe, expect, test } from 'vitest';
import { createDefaultHullmodTooltipPreset } from './defaultHullmodPreset';
import { normalizeTooltipPreset } from './tooltipPreset';

describe('tooltip preset model', () => {
  test('default preset recreates the screenshot hullmod structure', () => {
    const preset = createDefaultHullmodTooltipPreset();

    expect(preset.kind).toBe('hullmod-tooltip');
    expect(preset.hullmod.displayName).toBe('幅能配送器');
    expect(preset.blocks.some((block) => block.kind === 'section-heading' && block.text === 'S-插件增益')).toBe(true);
    expect(JSON.stringify(preset)).toContain('30 / 60 / 90 / 150');
    expect(JSON.stringify(preset)).toContain('10 / 20 / 30 / 50');
  });

  test('normalization preserves defaults while accepting partial persisted data', () => {
    const preset = normalizeTooltipPreset({
      hullmod: { displayName: '测试插件' },
      blocks: [{ id: 'summary', kind: 'paragraph', text: '测试 <hl>42</hl>', highlights: ['42'] }],
    });

    expect(preset.hullmod.displayName).toBe('测试插件');
    expect(preset.theme.panel.width).toBeGreaterThan(400);
    expect(preset.blocks[0].text).toContain('42');
  });
});
```

- [ ] **Step 2: Run tests and verify RED**

Run:

```bash
cd tools/tooltip-style-editor
npm test -- src/model/tooltipPreset.test.ts
```

Expected: fails because project/model files do not exist yet.

- [ ] **Step 3: Create minimal Vite project skeleton**

Use scripts matching the existing preview tool:

```json
{
  "name": "astd-tooltip-style-editor",
  "private": true,
  "version": "0.1.0",
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "tsc && vite build",
    "test": "vitest",
    "test:run": "vitest run"
  },
  "dependencies": {
    "@vitejs/plugin-react": "latest",
    "vite": "latest",
    "typescript": "latest",
    "react": "latest",
    "react-dom": "latest"
  },
  "devDependencies": {
    "vitest": "latest",
    "jsdom": "latest",
    "@testing-library/react": "latest",
    "@testing-library/jest-dom": "latest",
    "@testing-library/user-event": "latest",
    "@types/react": "latest",
    "@types/react-dom": "latest"
  }
}
```

- [ ] **Step 4: Implement `tooltipPreset.ts`**

Include:

```ts
export type TooltipBlockKind = 'paragraph' | 'section-heading' | 'spacer' | 'hint-line';

export interface Rgba {
  r: number;
  g: number;
  b: number;
  a: number;
}

export interface TooltipTextHighlight {
  value: string;
  colorRole?: 'accent' | 'warning' | 'positive' | 'muted';
}

export interface TooltipBlock {
  id: string;
  kind: TooltipBlockKind;
  text: string;
  highlights?: TooltipTextHighlight[] | string[];
  padTop?: number;
  align?: 'start' | 'center' | 'end';
}

export interface TooltipPreset {
  storageVersion: 'tooltip-style-editor/v1';
  kind: 'hullmod-tooltip';
  hullmod: {
    id: string;
    displayName: string;
    designType: string;
    tierLabel: string;
    iconLabel: string;
    opCost: number;
  };
  theme: {
    panel: {
      width: number;
      minHeight: number;
      borderColor: Rgba;
      backgroundColor: Rgba;
    };
    text: {
      title: Rgba;
      body: Rgba;
      muted: Rgba;
      warning: Rgba;
      positive: Rgba;
    };
    section: {
      backgroundColor: Rgba;
      textColor: Rgba;
    };
  };
  background: {
    shaderId: string;
    fragmentShader: string;
    uniforms: Record<string, number | Rgba>;
  };
  blocks: TooltipBlock[];
}
```

Implement `normalizeTooltipPreset(partial)` by deep-merging onto `createDefaultHullmodTooltipPreset()`.

- [ ] **Step 5: Implement `defaultHullmodPreset.ts`**

Default content must visibly match the supplied screenshot:

- title: `幅能配送器`
- design type: `设计类型： 普通`
- first paragraph with `30 / 60 / 90 / 150` highlighted as warning.
- section heading: `S-插件增益`
- second paragraph with `10 / 20 / 30 / 50` highlighted as warning.
- follow-up description text.
- hint line: `按 F2 来打开数据百科`
- op cost: `20`

- [ ] **Step 6: Run model tests and verify GREEN**

Run:

```bash
cd tools/tooltip-style-editor
npm test -- src/model/tooltipPreset.test.ts
```

Expected: pass.

## Task 2: Deterministic Tooltip Layout and Material Design 3 Editor Shell

**Files:**
- Modify: `tools/tooltip-style-editor/src/App.tsx`
- Create: `tools/tooltip-style-editor/src/App.css`
- Create: `tools/tooltip-style-editor/src/render/tooltipLayout.ts`
- Create: `tools/tooltip-style-editor/src/render/tooltipLayout.test.ts`
- Create: `tools/tooltip-style-editor/src/ui/TooltipPreview.tsx`
- Create: `tools/tooltip-style-editor/src/ui/TooltipPreview.test.tsx`
- Create: `tools/tooltip-style-editor/src/ui/BlockEditor.tsx`
- Create: `tools/tooltip-style-editor/src/ui/ThemeEditor.tsx`

- [ ] **Step 1: Write layout and preview tests first**

`tooltipLayout.test.ts` should verify:

```ts
import { describe, expect, test } from 'vitest';
import { estimateTooltipLayout } from './tooltipLayout';
import { createDefaultHullmodTooltipPreset } from '../model/defaultHullmodPreset';

describe('tooltip layout', () => {
  test('estimates a stable non-zero layout for the default hullmod tooltip', () => {
    const layout = estimateTooltipLayout(createDefaultHullmodTooltipPreset());
    expect(layout.width).toBeGreaterThan(400);
    expect(layout.height).toBeGreaterThan(220);
    expect(layout.blocks).toHaveLength(createDefaultHullmodTooltipPreset().blocks.length);
  });

  test('long edited text increases paragraph line count', () => {
    const preset = createDefaultHullmodTooltipPreset();
    preset.blocks[1].text = '很长的测试文本'.repeat(80);
    const layout = estimateTooltipLayout(preset);
    const paragraph = layout.blocks.find((block) => block.id === preset.blocks[1].id);
    expect(paragraph?.lineCount).toBeGreaterThan(2);
  });
});
```

`TooltipPreview.test.tsx` should verify:

```tsx
import { render, screen } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import { createDefaultHullmodTooltipPreset } from '../model/defaultHullmodPreset';
import { TooltipPreview } from './TooltipPreview';

describe('TooltipPreview', () => {
  test('renders editable hullmod tooltip content', () => {
    render(<TooltipPreview preset={createDefaultHullmodTooltipPreset()} />);

    expect(screen.getByText('幅能配送器')).toBeInTheDocument();
    expect(screen.getByText('S-插件增益')).toBeInTheDocument();
    expect(screen.getByText('20')).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run tests and verify RED**

Run:

```bash
cd tools/tooltip-style-editor
npm test -- src/render/tooltipLayout.test.ts src/ui/TooltipPreview.test.tsx
```

Expected: fails because renderer/UI files do not exist yet.

- [ ] **Step 3: Implement `tooltipLayout.ts`**

Use deterministic approximate text metrics, not browser APIs:

- CJK character width: `16`
- ASCII character width: `8`
- paragraph line height: `24`
- heading line height: `24`
- base horizontal padding: `18`
- block pads come from block config or sane defaults

Return:

```ts
export interface TooltipBlockLayout {
  id: string;
  top: number;
  height: number;
  lineCount: number;
}

export interface TooltipLayout {
  width: number;
  height: number;
  blocks: TooltipBlockLayout[];
}
```

- [ ] **Step 4: Implement `TooltipPreview.tsx`**

Render:

- outer cyan border.
- dark translucent panel.
- title row.
- design type row.
- paragraph blocks with highlighted substrings.
- green section heading bar.
- bottom hint line.
- bottom-right op cost pill.

The preview may use DOM for text and a WebGL canvas behind it in Task 3. Keep markup compatible with that.

- [ ] **Step 5: Implement `BlockEditor.tsx`**

Support:

- edit hullmod display name.
- edit design type.
- edit each text block through textarea/input.
- edit heading text.
- update preset immutably through `onPresetChange`.

- [ ] **Step 6: Implement `ThemeEditor.tsx`**

Support minimal initial controls:

- panel width.
- border color.
- title color.
- warning/highlight color.
- section background color.

- [ ] **Step 7: Implement `App.tsx` and `App.css` with Material Design 3 styling**

Requirements:

- App layout: top app bar, left preview pane, right editor pane with tabs or segmented controls.
- Use MD3-like tokens:
  - `--md-sys-color-primary`
  - `--md-sys-color-on-primary`
  - `--md-sys-color-surface`
  - `--md-sys-color-surface-container`
  - `--md-sys-color-outline`
  - `--md-sys-shape-corner-medium`
- Avoid the projectile editor's current neon panel style for the editor chrome.
- The preview itself should retain Starsector-like styling.
- Persist preset to `localStorage` under `astd-tooltip-style-editor-preset`.

- [ ] **Step 8: Run tests and verify GREEN**

Run:

```bash
cd tools/tooltip-style-editor
npm test -- src/render/tooltipLayout.test.ts src/ui/TooltipPreview.test.tsx
```

Expected: pass.

## Task 3: WebGL Shader Background Renderer and Shader Editor

**Files:**
- Create: `tools/tooltip-style-editor/src/render/webgl/shaderCompiler.ts`
- Create: `tools/tooltip-style-editor/src/render/webgl/fullscreenShaderRenderer.ts`
- Create: `tools/tooltip-style-editor/src/render/webgl/shaderCompiler.test.ts`
- Create: `tools/tooltip-style-editor/src/ui/ShaderEditor.tsx`
- Modify: `tools/tooltip-style-editor/src/ui/TooltipPreview.tsx`
- Modify: `tools/tooltip-style-editor/src/App.tsx`

- [ ] **Step 1: Write shader compiler tests first**

Use an injected fake WebGL context or function-level seams. Test:

```ts
import { describe, expect, test } from 'vitest';
import { createDefaultFragmentShader, validateFragmentShaderSource } from './shaderCompiler';

describe('shader compiler helpers', () => {
  test('default shader source uses WebGL 1 compatible uniforms', () => {
    const source = createDefaultFragmentShader();
    expect(source).toContain('precision mediump float');
    expect(source).toContain('uniform float u_time');
    expect(source).toContain('uniform vec2 u_resolution');
  });

  test('validation rejects obvious WebGL 2 only syntax', () => {
    const result = validateFragmentShaderSource('#version 300 es\nout vec4 color;');
    expect(result.ok).toBe(false);
  });
});
```

- [ ] **Step 2: Run tests and verify RED**

Run:

```bash
cd tools/tooltip-style-editor
npm test -- src/render/webgl/shaderCompiler.test.ts
```

Expected: fails because shader files do not exist.

- [ ] **Step 3: Implement `shaderCompiler.ts`**

Expose:

```ts
export interface ShaderValidationResult {
  ok: boolean;
  message?: string;
}

export function createDefaultFragmentShader(): string;
export function validateFragmentShaderSource(source: string): ShaderValidationResult;
export function compileShaderProgram(gl: WebGLRenderingContext, fragmentSource: string): WebGLProgram;
```

Validation should reject:

- `#version 300`
- `out vec4`
- `texture(`

Allow GLSL ES 1.00 style:

- `gl_FragColor`
- `texture2D`
- `precision mediump float`

- [ ] **Step 4: Implement `fullscreenShaderRenderer.ts`**

Implement a small class:

```ts
export class FullscreenShaderRenderer {
  constructor(canvas: HTMLCanvasElement);
  setFragmentShader(source: string): { ok: true } | { ok: false; message: string };
  render(input: {
    timeSeconds: number;
    resolution: [number, number];
    primaryColor: [number, number, number, number];
    accentColor: [number, number, number, number];
    intensity: number;
  }): void;
  dispose(): void;
}
```

Use one fullscreen triangle or quad. Uniforms:

- `u_time`
- `u_resolution`
- `u_primaryColor`
- `u_accentColor`
- `u_intensity`

- [ ] **Step 5: Add WebGL canvas to `TooltipPreview.tsx`**

Place shader canvas below tooltip content and clipped inside the tooltip panel.

Behavior:

- compile shader when source changes.
- animate with `requestAnimationFrame`.
- show compile error text in preview if invalid.
- keep DOM text readable over shader background.

- [ ] **Step 6: Implement `ShaderEditor.tsx`**

Controls:

- textarea for fragment shader source.
- intensity slider.
- primary/accent color controls.
- reset to default shader button.
- compile error display.

- [ ] **Step 7: Run shader tests and a full app test pass**

Run:

```bash
cd tools/tooltip-style-editor
npm test -- src/render/webgl/shaderCompiler.test.ts
npm test:run
```

Expected: pass.

## Task 4: Export Pipeline, Build Verification, and README

**Files:**
- Create: `tools/tooltip-style-editor/src/export/gameTooltipExport.ts`
- Create: `tools/tooltip-style-editor/src/export/kotlinTooltipExport.ts`
- Create: `tools/tooltip-style-editor/src/export/gameTooltipExport.test.ts`
- Create: `tools/tooltip-style-editor/README.md`
- Modify: `tools/tooltip-style-editor/src/App.tsx`

- [ ] **Step 1: Write export tests first**

Test:

```ts
import { describe, expect, test } from 'vitest';
import { createDefaultHullmodTooltipPreset } from '../model/defaultHullmodPreset';
import { serializeGameTooltipExport } from './gameTooltipExport';
import { formatTooltipKotlinScaffold } from './kotlinTooltipExport';

describe('tooltip exports', () => {
  test('serializes stable JSON with shader and blocks', () => {
    const json = serializeGameTooltipExport(createDefaultHullmodTooltipPreset());
    expect(json).toContain('"kind": "hullmod-tooltip"');
    expect(json).toContain('"fragmentShader"');
    expect(json).toContain('S-插件增益');
  });

  test('formats Kotlin scaffold for TooltipMakerAPI integration', () => {
    const kotlin = formatTooltipKotlinScaffold(createDefaultHullmodTooltipPreset());
    expect(kotlin).toContain('TooltipMakerAPI');
    expect(kotlin).toContain('addPostDescriptionSection');
    expect(kotlin).toContain('ASTDShaderTooltipBackground');
  });
});
```

- [ ] **Step 2: Run tests and verify RED**

Run:

```bash
cd tools/tooltip-style-editor
npm test -- src/export/gameTooltipExport.test.ts
```

Expected: fails because export files do not exist.

- [ ] **Step 3: Implement JSON export**

`serializeGameTooltipExport(preset)` should return pretty JSON with:

- schema version.
- hullmod metadata.
- theme.
- background shader.
- block list.

- [ ] **Step 4: Implement Kotlin scaffold export**

It should produce a clear scaffold string, not final game code:

- import mentions for `TooltipMakerAPI`.
- a sample `addPostDescriptionSection` method.
- comments pointing to future `ASTDShaderTooltipBackground`.
- generated `tooltip.addPara(...)` and `tooltip.addSectionHeading(...)` style calls.

Do not hardcode final in-game renderer implementation in this web-tool task.

- [ ] **Step 5: Add export buttons to `App.tsx`**

Support:

- copy JSON export to clipboard.
- copy Kotlin scaffold to clipboard.
- reset preset.

If clipboard is unavailable, show export text in a readonly textarea.

- [ ] **Step 6: Write README**

Document:

- install.
- dev.
- build.
- test.
- scope.
- game-realizable shader constraints.
- relationship to `projectile-vfx-preview`.

- [ ] **Step 7: Run full verification**

Run:

```bash
cd tools/tooltip-style-editor
npm test:run
npm run build
```

Expected: all tests pass and production build succeeds.

## Final Verification

After all tasks:

- Run `git status --short`.
- Confirm only intended files under `docs/superpowers/plans/2026-05-24-tooltip-style-editor.md` and `tools/tooltip-style-editor/` changed, plus any package lock created inside that tool.
- Run:

```bash
cd tools/tooltip-style-editor
npm test:run
npm run build
```

- If a dev server is needed for user preview, run:

```bash
cd tools/tooltip-style-editor
npm run dev -- --host 127.0.0.1
```

Report the local URL.
