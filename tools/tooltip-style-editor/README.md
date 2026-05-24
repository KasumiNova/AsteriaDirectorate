# Tooltip Style Editor

Web tool for prototyping ASTD hullmod tooltip content, theme colors, layout, and GLSL background shaders. The editor exports a JSON style payload plus a Kotlin scaffold for later game-side wiring.

## Install

```bash
npm install
```

## Development

```bash
npm run dev
```

Vite prints the local URL. The app stores the current preset in browser local storage under `astd-tooltip-style-editor-preset`.

## Build

```bash
npm run build
```

The production bundle is emitted by Vite after TypeScript compilation.

## Test

```bash
npm test
npm run test:run
```

Focused export pipeline test:

```bash
npm test -- src/export/gameTooltipExport.test.ts
```

## Scope

This tool edits and previews a hullmod tooltip preset in the browser. It supports:

- content blocks for paragraphs, section headings, spacers, and hint lines
- theme color and panel width controls
- editable fragment shader source and uniforms
- WebGL preview rendering
- JSON export for data handoff
- Kotlin scaffold export for implementation guidance

It does not implement the final in-game Kotlin renderer. The Kotlin export is intentionally a scaffold showing expected `TooltipMakerAPI` calls and where an `ASTDShaderTooltipBackground` integration should be wired later.

## WebGL And GLSL Constraints

The preview renderer targets browser WebGL with GLSL ES 1.00 fragment shaders. Shader source should keep these constraints in mind:

- include a precision declaration such as `precision mediump float;`
- use `gl_FragCoord` and `gl_FragColor`, not modern GLSL output variables
- avoid desktop-only GLSL features, geometry stages, and compute features
- keep uniform names aligned with the editor-provided values
- treat the browser preview as an approximation of the final Starsector renderer

## Export Pipeline

`src/export/gameTooltipExport.ts` produces the game-facing JSON payload:

- `schemaVersion`
- `kind`
- `hullmod`
- `theme`
- `background`
- `blocks`

`src/export/kotlinTooltipExport.ts` produces a readable scaffold string. It is meant to be copied into implementation notes or used as a starting point when the game-side renderer exists.

## Relationship To `projectile-vfx-preview`

`tools/projectile-vfx-preview/` is a separate VFX preview tool for projectile effects. This tooltip editor reuses the same general browser-tool approach, but it owns a different domain: tooltip layout, text styling, and shader-backed panel backgrounds. Changes here should stay inside `tools/tooltip-style-editor/` unless a broader tool-platform refactor is explicitly planned.
