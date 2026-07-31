import { describe, expect, it, vi } from 'vitest';
import { createDefaultBeamPreset } from '../model/beamPreset';
import { renderBeamPreview } from './beamPreviewRenderer';

type MockCanvasContext2D = {
  fill: ReturnType<typeof vi.fn>;
  stroke: ReturnType<typeof vi.fn>;
} & Record<string, unknown>;

describe('renderBeamPreview', () => {
  it('reports only enabled layers as rendered', () => {
    const preset = createDefaultBeamPreset();
    const ctx = createMockContext2D();
    const summary = renderBeamPreview(ctx as unknown as CanvasRenderingContext2D, {
      ...preset,
      layers: preset.layers.map((layer, index) => ({ ...layer, enabled: index === 0 })),
    }, { width: 900, height: 480, timeSeconds: 0 });

    expect(summary.enabledLayerCount).toBe(1);
    expect(summary.bounds.minX).toBeLessThan(900 / 2);
    expect(summary.bounds.maxX).toBeGreaterThan(900 / 2);
    expect(ctx.fill).toHaveBeenCalled();
    expect(ctx.stroke).toHaveBeenCalled();
  });

  it('centers authored beam geometry in the preview viewport', () => {
    const preset = createDefaultBeamPreset();
    const ctx = createMockContext2D();
    const summary = renderBeamPreview(ctx as unknown as CanvasRenderingContext2D, preset, { width: 900, height: 480, timeSeconds: 0 });

    expect((summary.bounds.minX + summary.bounds.maxX) * 0.5).toBeCloseTo(450, 5);
    expect((summary.bounds.minY + summary.bounds.maxY) * 0.5).toBeCloseTo(240, 5);
  });

  it('uses filled mesh bloom instead of segmented bloom strokes', () => {
    const preset = createDefaultBeamPreset();
    const ctx = createMockContext2D();
    renderBeamPreview(ctx as unknown as CanvasRenderingContext2D, preset, { width: 900, height: 480, timeSeconds: 0 });

    expect(ctx.stroke.mock.calls.length).toBeLessThan(60);
    expect(ctx.fill.mock.calls.length).toBeGreaterThan(100);
  });
});

function createMockContext2D(): MockCanvasContext2D {
  const context = {
    clearRect: vi.fn(),
    createLinearGradient: vi.fn(() => ({ addColorStop: vi.fn() } as unknown as CanvasGradient)),
    fillRect: vi.fn(),
    beginPath: vi.fn(),
    moveTo: vi.fn(),
    lineTo: vi.fn(),
    stroke: vi.fn(),
    fill: vi.fn(),
    arc: vi.fn(),
    save: vi.fn(),
    restore: vi.fn(),
    closePath: vi.fn(),
    fillStyle: '',
    strokeStyle: '',
    lineWidth: 1,
    lineCap: 'round',
    lineJoin: 'round',
    filter: 'none',
    globalCompositeOperation: 'source-over',
  };
  return context;
}
