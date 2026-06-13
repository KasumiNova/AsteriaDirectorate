# Game VFX Preview Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build `tools/game-vfx-preview/`, a generic MD3 + WebGL browser workbench for game VFX previews.

**Architecture:** Vite React orchestrates MD3 web components and typed effect state. WebGL rendering lives behind focused renderer adapters and receives shader-friendly uniforms from typed presets.

**Tech Stack:** TypeScript, Vite, React, `@material/web`, WebGL, Vitest, Testing Library.

---

### Task 1: Create Project Skeleton And RED Tests

**Files:**
- Create: `tools/game-vfx-preview/package.json`
- Create: `tools/game-vfx-preview/src/model/effectPreset.test.ts`
- Create: `tools/game-vfx-preview/src/render/webgl/starburstShader.test.ts`
- Create: `tools/game-vfx-preview/src/ui/materialUsage.test.ts`

- [x] Write tests for generic effect registry, shader-source contract, and MD3 component usage.
- [x] Run `npm run test:run` and verify tests fail because implementation files are missing.

### Task 2: Implement Preset Model And Shader Contract

**Files:**
- Create: `tools/game-vfx-preview/src/model/effectPreset.ts`
- Create: `tools/game-vfx-preview/src/render/webgl/starburstShader.ts`

- [x] Implement `rotating-blue-starburst` as the first generic `EffectPreset`.
- [x] Implement shader source and uniform metadata.
- [x] Run `npm run test:run` and verify model/shader tests pass.

### Task 3: Implement MD3 React UI

**Files:**
- Create: `tools/game-vfx-preview/src/App.tsx`
- Create: `tools/game-vfx-preview/src/App.css`
- Create: `tools/game-vfx-preview/src/ui/materialComponents.ts`

- [x] Use `@material/web` components for buttons, sliders, switch, select, text field, tabs, and elevated cards.
- [x] Keep CSS limited to layout, tokens, and canvas sizing.
- [x] Run `npm run test:run` and verify MD3 usage tests pass.

### Task 4: Implement WebGL Preview

**Files:**
- Create: `tools/game-vfx-preview/src/render/webgl/starburstRenderer.ts`
- Create: `tools/game-vfx-preview/src/ui/PreviewViewport.tsx`

- [x] Compile full-screen vertex and fragment shaders.
- [x] Drive uniforms from effect state and animation time.
- [x] Render a rotating blue starburst through WebGL.

### Task 5: Verify

- [x] Run `npm install`.
- [x] Run `npm run test:run`.
- [x] Run `npm run build`.
- [ ] Start `npm run dev -- --host 127.0.0.1`, open the page, and verify canvas content in browser.
