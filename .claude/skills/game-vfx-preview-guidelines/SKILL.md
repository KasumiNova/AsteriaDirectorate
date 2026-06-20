---
name: "game-vfx-preview-guidelines"
description: "Use when creating or modifying the generic game VFX preview tool under tools/game-vfx-preview, especially UI, Material Design 3, WebGL renderer, effect presets, or preview validation."
---

# Game VFX Preview Guidelines

## Scope

This skill applies to `tools/game-vfx-preview/`, the generic browser preview workbench for game visual effects. Do not apply these rules to older focused tools unless the task explicitly migrates them.

## Project Conventions

- Keep the tool independent from Starsector runtime code.
- Use Vite + React + TypeScript.
- Keep reusable effect definitions in `src/model/`.
- Keep WebGL code in `src/render/webgl/`.
- Keep React screens thin: state orchestration belongs in `src/App.tsx`, rendering math belongs outside React.
- Add tests before behavior changes. Prefer deterministic model, shader-source, and renderer-adapter tests over pixel tests unless browser verification is required.

## UI Rules

- Use a Material Design 3 component library first. The default library is `@material/web`.
- Do not handwrite custom button, switch, slider, text field, select, tab, card, or dialog styles.
- Handwritten CSS is allowed only for app layout, viewport sizing, canvas sizing, MD3 design tokens, and minor spacing glue.
- If a needed MD3 component is missing, document the gap in code or the implementation plan before using a native element.
- Keep text labels concise and tool-like. Avoid instructional marketing copy inside the app.

## Rendering Rules

- Prefer WebGL over Canvas 2D for VFX rendering.
- Model effects as shader/mesh-friendly data so they can be ported to LWJGL with minimal reinterpretation.
- Avoid Canvas-style immediate drawing semantics such as line strokes for core effects.
- The first bundled effect preset is `rotating-blue-starburst`; it should be implemented as a full-screen WebGL shader pass, not a stack of 2D lines.
- Expose effect parameters through typed presets and uniforms.

## Validation

- Run `npm run test:run` in `tools/game-vfx-preview/`.
- Run `npm run build` in `tools/game-vfx-preview/`.
- For visual changes, run the dev server and verify the WebGL canvas is nonblank in a real browser.
