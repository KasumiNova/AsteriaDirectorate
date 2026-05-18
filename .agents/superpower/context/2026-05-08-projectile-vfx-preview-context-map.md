## Context Map: projectile-vfx-preview compact editors + Kotlin export

### Primary Files (directly modified)
- `src/model/preset.ts` — preset schema and defaults; likely needs editor/export-friendly helpers and stable grouping metadata.
- `src/model/parsePreset.ts` — JSON import path; likely needs a shared serializer/export companion and stricter round-trip guarantees.
- `src/ui/ConfigPanel.tsx` — current JSON drawer; likely becomes a compact preset-level editor shell plus export actions.
- `src/ui/EntityInspector.tsx` — current single-trail editor; needs expansion into compact editors for trail, sprite, and segment entities.
- `src/ui/VersionCompare.tsx` — snapshot/history behavior; likely remains as preset compare support and may share export or preset clone utilities.
- `src/ui/TimelineControls.tsx` — timeline editing surface; may move into a compact editor group for duration/FPS/playback settings.
- `src/ui/PreviewCanvas.tsx` — preview viewport depends on preset camera/simulation state; UI changes must preserve rendering inputs.
- `src/App.tsx` — layout orchestration; will need panel rearrangement to host compact editors and export controls.
- `src/App.css` — layout and visual density rules; likely needs a compact editor grid, card rows, and reusable field styling.

### Secondary Files (may need updates)
- `src/model/preset.test.ts` — default preset shape tests; likely updated if helper fields or exports are added.
- `src/model/parsePreset.test.ts` — round-trip and validation tests; likely extended for exporter symmetry.
- `src/ui/ConfigPanel.test.tsx` — JSON apply/export expectations; likely updated when export moves to Kotlin code.
- `src/ui/EntityInspector.test.tsx` — field editing tests; likely expanded for compact editors and entity coverage.
- `src/ui/PreviewCanvas.test.tsx` — layout/accessibility assertions; may need updates after panel rearrangement.
- `src/ui/VersionCompare.test.tsx` — history snapshot behavior; likely remains useful for preset cloning.
- `src/render/trailGeometry.ts` — mesh builder defines visual look; keep untouched unless a model field change demands it.
- `src/render/previewOverlayRenderer.ts` — preview painter uses preset values directly; must stay aligned with editor defaults.
- `src/render/webglTrailRenderer.ts` — shader uniforms and blend path define look; keep stable to preserve appearance.
- `src/sim/timeline.ts` — playback timing; likely reused by any timeline editor.
- `README.md` — usage docs for JSON import/export; likely needs Kotlin export instructions.

### Test Coverage
- `src/model/preset.test.ts` — covers default preset shape.
- `src/model/parsePreset.test.ts` — covers JSON parse/format round-trip.
- `src/ui/ConfigPanel.test.tsx` — covers import/export interactions.
- `src/ui/EntityInspector.test.tsx` — covers trail field editing.
- `src/ui/PreviewCanvas.test.tsx` — covers preview accessibility and WebGL fallback.
- `src/render/trailGeometry.test.ts` — covers geometry that directly affects projectile appearance.
- `src/render/webglTrailRenderer.test.ts` — covers shader strings and renderer plumbing.

### Patterns to Follow
- `App.tsx` already treats `preset` as the single source of truth and passes it down to preview, timeline, and editors.
- `ConfigPanel.tsx` already centralizes import/export behavior; a Kotlin exporter can follow the same action pattern.
- `EntityInspector.tsx` already uses local patch helpers for immutable preset updates; that pattern fits compact field editors.
- `trailGeometry.ts` shows which fields are visually critical: nodes, width taper, color taper, emissive taper, texture scroll, and blend mode.
- `previewOverlayRenderer.ts` shows the preview camera and simulation fields influence the visual result even though they are not trail geometry.

### Suggested Change Sequence
1. Add a shared preset field/export utility layer in `src/model/` so compact editors and Kotlin export use the same data shape.
2. Split UI into compact editor components for trail, sprite, segment, timeline, camera, and simulation.
3. Replace the JSON drawer in `ConfigPanel.tsx` with a compact preset shell that keeps import and adds Kotlin export.
4. Update `App.tsx` and `App.css` for the denser layout and editor grouping.
5. Extend tests for editor coverage and add export-string tests that lock literal values and formatting.
6. Verify preview render tests still pass with unchanged render-path inputs.

### Breaking Changes
- A Kotlin exporter adds a new output format that should preserve the same numeric values and array ordering as the current preset model.
- A multi-entity editor changes the editing surface from one trail entity to all effect-related preset sections.
- Layout changes can alter the workflow while leaving render behavior intact.

### Risks
- Rounding or clamping in the editor or exporter can shift width, alpha taper, UV scroll, or color balance.
- Reordering trail nodes or regenerating default ages can change the beam silhouette.
- Changing default `timeline`, `previewCamera`, or `simulation` values can alter playback timing and apparent motion.
- Any renderer or geometry change can shift the projectile look; keep those code paths stable during editor/export work.
