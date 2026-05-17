import { describe, expect, it, vi } from 'vitest';
import { createDefaultPreset, type BoxUtilPreviewPreset } from '../model/preset';
import { createPreviewOverlayRenderer } from './previewOverlayRenderer';

class TestGradient {
  readonly stops: Array<{ offset: number; color: string }> = [];
  addColorStop(offset: number, color: string) {
    this.stops.push({ offset, color });
  }
}

class TestCanvasContext {
  clearRect = vi.fn();
  fillRect = vi.fn();
  beginPath = vi.fn();
  moveTo = vi.fn();
  lineTo = vi.fn();
  quadraticCurveTo = vi.fn();
  closePath = vi.fn();
  fill = vi.fn();
  stroke = vi.fn();
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

function renderWith(preset: BoxUtilPreviewPreset) {
  const ctx = new TestCanvasContext();
  const canvas = {
    width: 0,
    height: 0,
    getContext: vi.fn(() => ctx),
  } as unknown as HTMLCanvasElement;
  const renderer = createPreviewOverlayRenderer(canvas);
  expect(renderer).not.toBeNull();
  renderer?.resize(960, 540);
  renderer?.render(preset, 0.42);
  return ctx;
}

describe('preview overlay render graph', () => {
  it('skips head draw path when head layers are disabled', () => {
    const enabled = renderWith(createDefaultPreset());
    const disabledPreset = createDefaultPreset();
    disabledPreset.headLayers = disabledPreset.headLayers.map((layer) => ({ ...layer, enabled: false }));
    disabledPreset.ribbonDecorations = [];
    disabledPreset.trailEntities = disabledPreset.trailEntities.map((trail) => ({ ...trail, ribbonDecorations: [] }));

    const disabled = renderWith(disabledPreset);

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
});
