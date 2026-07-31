import { describe, expect, it } from 'vitest';
import { createDefaultBeamPreset } from '../model/beamPreset';
import { sampleBeamPath } from './beamGeometry';

describe('sampleBeamPath', () => {
  it('keeps straight endpoints and stable progress', () => {
    const preset = createDefaultBeamPreset();
    const samples = sampleBeamPath(preset, { sampleCount: 9 });

    expect(samples).toHaveLength(9);
    expect(samples[0]).toMatchObject({ x: preset.controlPoints[0].x, y: preset.controlPoints[0].y, progress: 0 });
    expect(samples[samples.length - 1]).toMatchObject({ x: preset.controlPoints[1].x, y: preset.controlPoints[1].y, progress: 1 });
  });

  it('samples curved midpoint away from the straight chord', () => {
    const preset = {
      ...createDefaultBeamPreset(),
      mode: 'curved' as const,
      controlPoints: [
        { x: 0, y: 0 },
        { x: 100, y: 120 },
        { x: 200, y: 0 },
      ],
    };

    const samples = sampleBeamPath(preset, { sampleCount: 5 });

    expect(samples[2].y).toBeGreaterThan(40);
    expect(samples[2].x).toBeCloseTo(100, 5);
  });

  it('returns finite continuous normals', () => {
    const preset = {
      ...createDefaultBeamPreset(),
      mode: 'curved' as const,
      controlPoints: [
        { x: 0, y: 0 },
        { x: 80, y: 110 },
        { x: 180, y: -20 },
        { x: 260, y: 30 },
      ],
    };

    const samples = sampleBeamPath(preset, { sampleCount: 16 });

    for (const sample of samples) {
      expect(Number.isFinite(sample.normalX)).toBe(true);
      expect(Number.isFinite(sample.normalY)).toBe(true);
      expect(Math.hypot(sample.normalX, sample.normalY)).toBeCloseTo(1, 5);
    }
  });
});
