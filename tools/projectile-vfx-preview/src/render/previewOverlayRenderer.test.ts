import { describe, expect, it, vi } from 'vitest';
import { createDefaultPreset, type BoxUtilPreviewPreset } from '../model/preset';
import { createPreviewOverlayRenderer, type PreviewTrajectoryMode } from './previewOverlayRenderer';

class TestGradient {
  readonly stops: Array<{ offset: number; color: string }> = [];
  addColorStop(offset: number, color: string) {
    this.stops.push({ offset, color });
  }
}

class TestCanvasContext {
  readonly paths: Array<Array<{ method: 'moveTo' | 'lineTo' | 'quadraticCurveTo'; args: number[] }>> = [];
  readonly strokes: Array<Array<{ method: 'moveTo' | 'lineTo' | 'quadraticCurveTo'; args: number[] }>> = [];
  private currentPath: Array<{ method: 'moveTo' | 'lineTo' | 'quadraticCurveTo'; args: number[] }> = [];
  clearRect = vi.fn();
  fillRect = vi.fn();
  beginPath = vi.fn(() => {
    this.currentPath = [];
  });
  moveTo = vi.fn((x: number, y: number) => {
    this.currentPath.push({ method: 'moveTo', args: [x, y] });
  });
  lineTo = vi.fn((x: number, y: number) => {
    this.currentPath.push({ method: 'lineTo', args: [x, y] });
  });
  quadraticCurveTo = vi.fn((cpx: number, cpy: number, x: number, y: number) => {
    this.currentPath.push({ method: 'quadraticCurveTo', args: [cpx, cpy, x, y] });
  });
  closePath = vi.fn();
  fill = vi.fn(() => {
    this.paths.push([...this.currentPath]);
  });
  stroke = vi.fn(() => {
    this.strokes.push([...this.currentPath]);
  });
  save = vi.fn();
  restore = vi.fn();
  translate = vi.fn();
  rotate = vi.fn();
  scale = vi.fn();
  clip = vi.fn();
  arc = vi.fn();
  createLinearGradient = vi.fn(() => new TestGradient());
  createRadialGradient = vi.fn(() => new TestGradient());
  globalCompositeOperation = 'source-over';
  fillStyle: unknown = '';
  strokeStyle: unknown = '';
  lineWidth = 1;
  lineCap = 'butt';
  lineJoin = 'miter';
  shadowBlur = 0;
  shadowColor = '';
  filter = '';
}

function largestFilledPathYRange(ctx: TestCanvasContext): number {
  return Math.max(
    ...ctx.paths.map((path) => {
      const ys = path.flatMap((entry) => entry.method === 'quadraticCurveTo' ? [entry.args[1], entry.args[3]] : [entry.args[1]]);
      return Math.max(...ys) - Math.min(...ys);
    }),
  );
}

function largestStrokeSegmentLength(ctx: TestCanvasContext): number {
  let largest = 0;
  for (const path of ctx.strokes) {
    if (path.length < 3) {
      continue;
    }
    let previous: number[] | null = null;
    for (const entry of path) {
      const point = entry.method === 'quadraticCurveTo' ? [entry.args[2], entry.args[3]] : entry.args;
      if (previous) {
        largest = Math.max(largest, Math.hypot(point[0] - previous[0], point[1] - previous[1]));
      }
      previous = point;
    }
  }
  return largest;
}

function renderWith(
  preset: BoxUtilPreviewPreset,
  trajectoryMode?: PreviewTrajectoryMode,
  layerVisibility?: Partial<{
    trail: boolean;
    head: boolean;
    glow: boolean;
    mist: boolean;
    sideWisps: boolean;
    ribbon: boolean;
  }>,
) {
  const ctx = new TestCanvasContext();
  const canvas = {
    width: 0,
    height: 0,
    getContext: vi.fn(() => ctx),
  } as unknown as HTMLCanvasElement;
  const renderer = createPreviewOverlayRenderer(canvas);
  expect(renderer).not.toBeNull();
  renderer?.resize(960, 540);
  renderer?.render(preset, 0.42, layerVisibility, trajectoryMode);
  return ctx;
}

describe('preview overlay render graph', () => {
  it('skips head draw path when head layers are disabled', () => {
    const headOnly = { trail: false, glow: false, mist: false, sideWisps: false, ribbon: false };
    const enabled = renderWith(createDefaultPreset(), undefined, headOnly);
    const disabledPreset = createDefaultPreset();
    disabledPreset.headLayers = disabledPreset.headLayers.map((layer) => ({ ...layer, enabled: false }));
    disabledPreset.ribbonDecorations = [];
    disabledPreset.trailEntities = disabledPreset.trailEntities.map((trail) => ({ ...trail, ribbonDecorations: [] }));

    const disabled = renderWith(disabledPreset, undefined, headOnly);

    expect(enabled.quadraticCurveTo.mock.calls.length).toBeGreaterThan(0);
    expect(disabled.quadraticCurveTo.mock.calls.length).toBe(0);
  });

  it('skips glow strokes when glow layers are disabled', () => {
    const enabled = renderWith(createDefaultPreset());
    const disabledPreset = createDefaultPreset();
    disabledPreset.glowLayers = disabledPreset.glowLayers.map((layer) => ({ ...layer, enabled: false }));

    const disabled = renderWith(disabledPreset);

    expect(enabled.createLinearGradient.mock.calls.length).toBeGreaterThan(disabled.createLinearGradient.mock.calls.length);
  });

  it('uses mist blobCount from the render graph', () => {
    const preset = createDefaultPreset();
    preset.mistLayers = preset.mistLayers.map((layer) => ({ ...layer, blobCount: 7 }));

    const ctx = renderWith(preset);

    expect(ctx.arc.mock.calls.length).toBe(7);
  });

  it('uses side wisp offsets from the render graph', () => {
    const preset = createDefaultPreset();
    preset.sideWispLayers = preset.sideWispLayers.map((layer) => ({ ...layer, offsets: [-3, 0, 3] }));

    const ctx = renderWith(preset);

    expect(ctx.lineTo.mock.calls.length).toBeGreaterThanOrEqual(3 * 2);
  });

  it('uses top-level ribbonDecorations and keeps world history sampling active', () => {
    const preset = createDefaultPreset();
    preset.trailEntities = preset.trailEntities.map((trail) => ({ ...trail, ribbonDecorations: [] }));
    preset.ribbonDecorations = preset.ribbonDecorations.map((ribbon) => ({ ...ribbon, enabled: true, renderMode: 'byLength', lengthScale: 0.2 }));

    const ctx = renderWith(preset);

    expect(ctx.quadraticCurveTo.mock.calls.length).toBeGreaterThan(0);
  });

  it('keeps straight trail body flat and bends curved trail body without changing the preset', () => {
    const preset = createDefaultPreset();
    preset.simulation.curveAmount = 0;
    const trailOnly = { head: false, glow: false, mist: false, sideWisps: false, ribbon: false };

    const straight = renderWith(preset, 'straight', trailOnly);
    const curved = renderWith(preset, 'curved', trailOnly);

    expect(largestFilledPathYRange(straight)).toBeLessThan(20);
    expect(largestFilledPathYRange(curved)).toBeGreaterThan(40);
    expect(straight.translate.mock.calls[0][1]).toBeCloseTo(270, 3);
    expect(curved.translate.mock.calls[0][1]).not.toBeCloseTo(straight.translate.mock.calls[0][1], 3);
    expect(preset.simulation.curveAmount).toBe(0);
  });

  it('curves projectile body geometry around the same history track as the head', () => {
    const preset = createDefaultPreset();
    preset.simulation.curveAmount = 96;
    const trailOnly = { head: false, glow: false, mist: false, sideWisps: false, ribbon: false };

    const straight = renderWith(preset, 'straight', trailOnly);
    const curved = renderWith(preset, 'curved', trailOnly);

    expect(largestFilledPathYRange(straight)).toBeLessThan(20);
    expect(largestFilledPathYRange(curved)).toBeGreaterThan(40);
    expect(curved.translate.mock.calls[0][1]).not.toBeCloseTo(straight.translate.mock.calls[0][1], 3);
  });

  it('rotates the projectile head along the curved trail tangent', () => {
    const preset = createDefaultPreset();
    preset.simulation.curveAmount = 96;
    const headOnly = { trail: false, glow: false, mist: false, sideWisps: false, ribbon: false };

    const straight = renderWith(preset, 'straight', headOnly);
    const curved = renderWith(preset, 'curved', headOnly);

    expect(straight.rotate.mock.calls[0][0]).toBeCloseTo(0, 3);
    expect(curved.rotate.mock.calls[0][0]).toBeLessThan(-0.2);
  });

  it('draws curved side wisps as sampled centerline paths instead of long chords', () => {
    const preset = createDefaultPreset();
    preset.simulation.curveAmount = 96;
    const sideWispsOnly = { trail: false, head: false, glow: false, mist: false, ribbon: false };

    const ctx = renderWith(preset, 'curved', sideWispsOnly);

    expect(largestStrokeSegmentLength(ctx)).toBeLessThan(44);
  });
});
