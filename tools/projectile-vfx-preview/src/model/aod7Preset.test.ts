import { describe, expect, it } from 'vitest';
import exportedAod7 from '../fixtures/aod7Preset.json';
import { createAod7Preset } from './aod7Preset';
import type { GameProjectileVfxPreset } from './gameExport';

describe('createAod7Preset', () => {
  it('uses the preview-local AOD-7 fixture as the editor baseline', () => {
    const exported = exportedAod7 as unknown as GameProjectileVfxPreset;
    const preset = createAod7Preset();

    expect(preset.name).toBe(exported.name);
    expect(preset.trailEntities[0].startWidth).toBe(exported.trailEntities[0].startWidth);
    expect(preset.lifecycle.layoutReferenceWidth).toBe(exported.lifecycle.layoutReferenceWidth);
    expect(preset.samplingPolicy.distanceWindow).toBe(exported.samplingPolicy.distanceWindow);
  });

  it('maps runtime ribbon amplitude and frequency back to preview field names', () => {
    const exported = exportedAod7 as unknown as GameProjectileVfxPreset;
    const preset = createAod7Preset();

    expect(preset.ribbonDecorations[0].waveAmplitude).toBe(exported.ribbonDecorations[0].amplitude);
    expect(preset.ribbonDecorations[0].waveFrequency).toBe(exported.ribbonDecorations[0].frequency);
    expect(preset.trailEntities[0].ribbonDecorations[0].waveAmplitude).toBe(exported.trailEntities[0].ribbonDecorations[0].amplitude);
  });
});
