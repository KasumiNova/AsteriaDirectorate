import { describe, expect, it } from 'vitest';
import { beamAlpha, dissolve, hermite01, layeredNoise, ribbonWave, sampleHistoryAt, shaderNoise, smoothstep, visibleLength } from './projectileVfxMath';

describe('projectile VFX shared math vectors', () => {
  it('matches scalar helper vectors', () => {
    expect(smoothstep(0.2, 0.8, 0.5)).toBeCloseTo(0.5, 4);
    expect(hermite01(0.35, 1.2, 0.3)).toBeCloseTo(0.4353125, 4);
    expect(shaderNoise(1.25, 2.5)).toBeCloseTo(0.7555694, 4);
    expect(layeredNoise(0.42, 3.7)).toBeCloseTo(0.4510680, 4);
  });

  it('matches sampling and lifecycle vectors', () => {
    expect(sampleHistoryAt([[0, 0], [10, 0], [20, 10]], 7.5, 5)).toEqual([15, 5]);
    expect(dissolve(1.0, 1.25, 0.6)).toBeCloseTo(0.5, 4);
    expect(beamAlpha(0.5)).toBeCloseTo(0.19, 4);
    expect(visibleLength(420, 0.5)).toBeCloseTo(226.8, 4);
  });

  it('matches ribbon wave vectors', () => {
    expect(ribbonWave('sine', 120, 0.42, 1.1, 1, 1.35, 4, 17, 0.48)).toBeCloseTo(-0.4573935, 4);
    expect(ribbonWave('noise', 120, 0.42, 1.1, 1, 1.35, 4, 17, 0.48)).toBeCloseTo(-0.1519367, 4);
    expect(ribbonWave('zigzag', 120, 0.42, 1.1, 1, 1.35, 4, 17, 0.48)).toBeCloseTo(-0.6449725, 4);
  });
});
