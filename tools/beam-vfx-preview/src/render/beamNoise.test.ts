import { describe, expect, it } from 'vitest';
import { beamNoiseAt } from './beamNoise';

describe('beamNoiseAt', () => {
  it('is deterministic for identical inputs', () => {
    expect(beamNoiseAt('core', 0.42, 1.25, 9)).toBe(beamNoiseAt('core', 0.42, 1.25, 9));
  });

  it('changes smoothly across nearby times', () => {
    const a = beamNoiseAt('core', 0.42, 1.25, 9);
    const b = beamNoiseAt('core', 0.42, 1.3, 9);

    expect(Math.abs(a - b)).toBeLessThan(0.2);
  });
});
