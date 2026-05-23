import { describe, expect, it, vi } from 'vitest';
import { createDefaultBeamPreset } from '../model/beamPreset';
import { renderBeamPreview } from './beamPreviewRenderer';

describe('renderBeamPreview', () => {
  it('reports only enabled layers as rendered', () => {
    const preset = createDefaultBeamPreset();
    const ctx = createMockContext2D();
    const summary = renderBeamPreview(ctx as unknown as CanvasRenderingContext2D, {
      ...preset,
      layers: preset.layers.map((layer, index) => ({ ...layer, enabled: index === 0 })),
    }, { width: 900, height: 480, timeSeconds: 0 });

    expect(summary.enabledLayerCount).toBe(1);
    expect(ctx.fill).toHaveBeenCalled();
    expect(ctx.stroke).toHaveBeenCalled();
  });
});

function createMockContext2D(): Partial<CanvasRenderingContext2D> {
  return {
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
    globalCompositeOperation: 'source-over',
  };
}
