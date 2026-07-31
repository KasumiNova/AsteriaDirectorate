import { describe, expect, it } from 'vitest';
import { previewBackingRatio } from './BeamPreviewCanvas';

describe('previewBackingRatio', () => {
  it('caps preview backing resolution to reduce GPU cost', () => {
    expect(previewBackingRatio(1700, 990, 2)).toBeCloseTo(1280 / 1700, 5);
  });

  it('does not render above 1x device pixel ratio', () => {
    expect(previewBackingRatio(900, 480, 2)).toBe(1);
  });
});
