import { describe, expect, it } from 'vitest';
import { createDefaultPreset } from './preset';

describe('createDefaultPreset', () => {
  it('creates BoxUtil preview entity collections', () => {
    const preset = createDefaultPreset();

    expect(Array.isArray(preset.trailEntities)).toBe(true);
  });

  it('creates a TrailEntity-like default trail', () => {
    const preset = createDefaultPreset();
    const trail = preset.trailEntities[0];

    expect(trail).toMatchObject({
      startWidth: expect.any(Number),
      endWidth: expect.any(Number),
      texturePixels: expect.any(Number),
      textureSpeed: expect.any(Number),
      uvOffset: expect.any(Number),
      startColor: expect.any(Array),
      endColor: expect.any(Array),
      startEmissive: expect.any(Array),
      endEmissive: expect.any(Array),
    });
  });

  it('defaults the timeline to 60 FPS', () => {
    const preset = createDefaultPreset();

    expect(preset.timeline.fps).toBe(60);
  });
});
