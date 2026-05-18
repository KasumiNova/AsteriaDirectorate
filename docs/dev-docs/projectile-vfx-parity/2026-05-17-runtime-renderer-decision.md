# Runtime Renderer Decision - Filled Body and Head

Scope: Task 3 `aod7_shot` filled body runtime layer, plus Task 4 filled head runtime layer.

## Decision

The runtime graph now inserts `ASTDProjectileVfxBodyRenderLayer` after glow and before side wisps/head:

1. `ASTDProjectileVfxTrailRenderLayer`
2. `ASTDProjectileVfxGlowRenderLayer`
3. `ASTDProjectileVfxBodyRenderLayer`
4. `ASTDProjectileVfxSideWispRenderLayer`
5. `ASTDProjectileVfxHeadRenderLayer`
6. `ASTDProjectileVfxMistRenderLayer`
7. `ASTDProjectileVfxRibbonRenderLayer`

This keeps the existing Starsector blending order mostly intact while adding the filled body before the sharper foreground details.

## BoxUtil Feasibility

`TrailEntity` remains the right path for the regular trail, glow bands, side wisps, and ribbons, but it does not fill the preview body's spear-shaped shoulder/tip polygon. It expresses a path with width/taper, not a nine-point filled polygon with per-vertex gradient semantics.

`SpriteEntity` would need a generated or morphed alpha mask for the body shape. Because the polygon changes with `visibleLength` and pulse, that would require either a large mask atlas or per-frame texture churn. Both are unsuitable for this dynamic layer.

## Implemented Runtime Shape

`ASTDProjectileVfxBodyRenderer` builds an additive triangle-fan mesh from:

- `ASTDProjectileVfxLayout.bodyPolygon(widthBase, visibleLength, pulse)`
- `ASTDProjectileVfxLayout.bodyGradientStops(baseLayer, pulse)`

The mesh is updated every frame from `context.visibleLength` and `context.beamAlpha`, and it carries additive blend intent plus the combat layer from the base trail layer. It does not allocate textures per frame.

Task 3B attaches this mesh data to `ASTDProjectileVfxBodyRenderManager`, a combat layered rendering plugin stored in `engine.customData`.

At runtime:

- `ASTDProjectileVfxBodyRenderLayer.create(engine, context)` creates one manager handle when `engine` is available.
- `advance(engine, context, amount)` refreshes the handle with the current mesh, projectile location, and render facing.
- `delete()` removes the handle from the manager.
- The manager renders active body meshes as GL triangles with additive blending and no per-frame texture allocation.
- Local mesh vertices are rotated around the projectile origin by `context.renderFacing`, then translated by `context.location`.

`ASTDProjectileVfxBodyRenderLayer.create(null, context)` still returns `false` for unit-test parity with the existing engine-less layer tests, and does not create a manager handle.

The implementation currently uses the mesh `combatLayer` for renderer active layers and render filtering. This keeps the filled body on the same Starsector layer as the base trail layer while leaving the render graph order unchanged.

## Task 4 Head Update

`ASTDProjectileVfxHeadRenderLayer` now uses the same custom GL triangle mesh manager instead of a BoxUtil `SegmentEntity` outline. This avoids the visible crossed/outlined shell artifact from rendering the head polygon as line segments.

The head mesh is built from `ASTDProjectileVfxLayout.headFillLayout(...)`:

- vertices come directly from `layout.vertices.asList()`;
- triangles use a closed polygon triangle fan;
- alpha is `context.beamAlpha * layout.headVisible * layer.alphaScale`, with runtime fade applied once by the layer handle update path;
- blending and combat layer stay aligned with the head/base trail layer data.

`create(null, context)` still returns `false` for unit-test and engine-less contexts, while a real combat engine creates or updates mesh handles through `ASTDProjectileVfxBodyRenderManager`.
