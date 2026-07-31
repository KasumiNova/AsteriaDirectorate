import { describe, expect, it } from 'vitest';
import { createDefaultPreset, Rgba } from '../model/preset';
import {
  projectileVfxFlightLayout,
  projectileVfxBodyGradientStops,
  projectileVfxBodyPolygon,
  projectileVfxGlowLineWidth,
  projectileVfxGlowLocalNodes,
  projectileVfxHeadColors,
  projectileVfxHeadFillLayout,
  projectileVfxHeadTrailScale,
  projectileVfxHeadVertices,
  projectileVfxSideWispLocalPaths,
  projectileVfxTrailLocalNodes,
  projectileVfxWidthBase,
} from './projectileVfxLayout';

describe('projectile VFX layout contract', () => {
  it('keeps AOD-7 head at origin and tail on negative X', () => {
    const preset = createDefaultPreset();
    const trail = preset.trailEntities[0];
    const nodes = projectileVfxTrailLocalNodes(trail.length);

    expect(nodes).toEqual([[-420, 0], [0, 0]]);
  });

  it('maps preview raw width to runtime visual width base', () => {
    const preset = createDefaultPreset();
    const trail = preset.trailEntities[0];
    const widthBase = projectileVfxWidthBase(trail);

    expect(widthBase).toBeCloseTo(3.5, 4);
    expect(projectileVfxGlowLineWidth(widthBase, preset.glowLayers[0])).toBeCloseTo(18.9, 4);
    expect(projectileVfxGlowLineWidth(widthBase, preset.sideWispLayers[0])).toBeCloseTo(0.7, 4);
  });

  it('matches Kotlin layout vectors for lifecycle and layers', () => {
    const preset = createDefaultPreset();
    const trail = preset.trailEntities[0];
    const widthBase = projectileVfxWidthBase(trail);
    const flight = projectileVfxFlightLayout(trail.length, 1.0, preset.lifecycle.durationSeconds, preset.lifecycle.dissolveStartRatio);
    const glowNodes = projectileVfxGlowLocalNodes(flight.visibleLength, preset.glowLayers[0]);
    const head = projectileVfxHeadVertices(preset.headLayers[0], 0.8, preset.lifecycle.projectileHeadSizeScale);
    const sideWisps = projectileVfxSideWispLocalPaths(preset.sideWispLayers[0], flight.visibleLength, widthBase);

    expect(flight.dissolve).toBeCloseTo(0.5, 4);
    expect(flight.beamAlpha).toBeCloseTo(0.19, 4);
    expect(flight.visibleLength).toBeCloseTo(226.8, 4);
    expect(glowNodes).toEqual([[-226.8, -0.36], [0, -0.36]]);
    expect(head.rearTop[0]).toBeCloseTo(-157.32, 4);
    expect(head.tip).toEqual([0, 0]);
    expect(sideWisps[0][0][0]).toBeCloseTo(-145.152, 4);
    expect(sideWisps[0][0][1]).toBeCloseTo(-7.35, 4);
  });

  it('scales projectile head from TrailEntity width base', () => {
    const preset = createDefaultPreset();
    const trail = preset.trailEntities[0];
    const defaultWidthBase = projectileVfxWidthBase(trail);
    const widerWidthBase = projectileVfxWidthBase({ ...trail, startWidth: trail.startWidth * 2 });
    const defaultHead = projectileVfxHeadVertices(preset.headLayers[0], 0.8, preset.lifecycle.projectileHeadSizeScale, defaultWidthBase);
    const widerHead = projectileVfxHeadVertices(preset.headLayers[0], 0.8, preset.lifecycle.projectileHeadSizeScale, widerWidthBase);
    const widthBaseRatio = widerWidthBase / defaultWidthBase;

    expect(projectileVfxHeadTrailScale(defaultWidthBase)).toBeCloseTo(0.583333, 4);
    expect(widerHead.rearTop[0]).toBeCloseTo(defaultHead.rearTop[0] * widthBaseRatio, 4);
    expect(widerHead.shoulderTop[1]).toBeCloseTo(defaultHead.shoulderTop[1] * widthBaseRatio, 4);
  });

  it('locks AOD-7 preview body geometry contract at full pulse', () => {
    const body = projectileVfxBodyPolygon(6, 420, 1);

    expect(body[4]).toEqual([0, 0]);
    expect(body.every(([x]) => x <= 0)).toBe(true);
    expect(Math.min(...body.map(([x]) => x))).toBeLessThan(0);
    expect(Math.max(...body.map(([, y]) => y))).toBeCloseTo(-Math.min(...body.map(([, y]) => y)), 4);
  });

  it('locks AOD-7 preview body gradient contract at full pulse', () => {
    const preset = createDefaultPreset();
    const trail = preset.trailEntities[0];
    const stops = projectileVfxBodyGradientStops(trail, 1);

    expect(stops.map((stop) => stop.offset)).toEqual([0, 0.24, 0.62, 0.84, 1]);
    expect(stops[0].alpha).toBe(0);
    expect(stops[1].alpha).toBeCloseTo(0.08, 4);
    expect(stops[2].alpha).toBeCloseTo(0.75, 4);
    expect(stops[3].alpha).toBeCloseTo(0.92, 4);
    expect(stops[4].alpha).toBe(0);
    expect(stops[4].css).toBe('rgba(255,255,255,0)');
  });

  it('locks AOD-7 preview head fill contract at full pulse', () => {
    const preset = createDefaultPreset();
    const trail = preset.trailEntities[0];
    const layer = preset.headLayers[0];
    const widthBase = projectileVfxWidthBase(trail);
    const layout = projectileVfxHeadFillLayout(trail, layer, preset.lifecycle.projectileHeadSizeScale, widthBase, 1);
    const vertices = projectileVfxHeadVertices(layer, layout.headVisible, preset.lifecycle.projectileHeadSizeScale, widthBase);

    expect(layout.vertices).toEqual(vertices);
    expect(layout.alpha).toBeCloseTo(1 * layout.headVisible * layer.alphaScale, 4);
  });

  it('derives projectile head colors from TrailEntity colors', () => {
    const preset = createDefaultPreset();
    const trail = preset.trailEntities[0];
    const layer = preset.headLayers[0];
    const defaultColors = projectileVfxHeadColors(trail, layer);
    const redTrail = {
      ...trail,
      startColor: [1, 0, 0, 1] as Rgba,
      startEmissive: [1, 0, 0, 1] as Rgba,
      endColor: [0.4, 0, 0, 0.5] as Rgba,
      endEmissive: [0.6, 0, 0, 0.5] as Rgba,
    };
    const redColors = projectileVfxHeadColors(redTrail, layer);

    expect(defaultColors.mid[2]).toBeGreaterThan(defaultColors.mid[0]);
    expect(redColors.mid[0]).toBeGreaterThan(0);
    expect(redColors.mid[1]).toBe(0);
    expect(redColors.mid[2]).toBe(0);
    expect(redColors.end[0]).toBeGreaterThan(redColors.end[1]);
  });
});
