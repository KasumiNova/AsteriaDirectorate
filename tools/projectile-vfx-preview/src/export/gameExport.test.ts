import { describe, expect, it } from 'vitest';
import { createDefaultPreset } from '../model/preset';
import { serializeGameExportPreset, toGameExportPreset } from './gameExport';

describe('game projectile vfx export boundary', () => {
  it('omits preview-only fields from the game export preset', () => {
    const exported = toGameExportPreset(createDefaultPreset());
    const json = JSON.stringify(exported);

    expect(exported).toHaveProperty('name');
    expect(exported).toHaveProperty('trailEntities');
    expect(exported).toHaveProperty('headLayers');
    expect(exported).toHaveProperty('glowLayers');
    expect(exported).toHaveProperty('mistLayers');
    expect(exported).toHaveProperty('sideWispLayers');
    expect(exported).toHaveProperty('ribbonDecorations');
    expect(exported).toHaveProperty('hooks');
    expect(exported).toHaveProperty('lifecycle');
    expect(exported).toHaveProperty('samplingPolicy');

    expect(json).not.toContain('timeline');
    expect(json).not.toContain('simulation');
    expect(json).not.toContain('previewCamera');
    expect(json).not.toContain('canvas');
    expect(json).not.toContain('backdrop');
    expect(json).not.toContain('grid');
    expect(json).not.toContain('curve');
    expect(json).not.toContain('loop');
    expect(allKeys(exported)).not.toContain('projectileVelocity');
  });

  it('exports a complete runtime render graph for the default preset', () => {
    const exported = toGameExportPreset(createDefaultPreset());

    expect(exported.trailEntities.length).toBeGreaterThan(0);
    expect(exported.trailEntities[0].length).toBeGreaterThan(0);
    expect(exported.trailEntities[0].anchorMode).toBe('headLocked');
    expect(exported.trailEntities[0].orientationMode).toBe('projectileVelocity');
    expect(exported.headLayers.length).toBeGreaterThan(0);
    expect(exported.glowLayers).toHaveLength(4);
    expect(exported.mistLayers.length).toBeGreaterThan(0);
    expect(exported.sideWispLayers.length).toBeGreaterThan(0);
    expect(exported.ribbonDecorations.length).toBeGreaterThan(0);
    expect(exported.ribbonDecorations[0]).toHaveProperty('amplitude');
    expect(exported.ribbonDecorations[0]).toHaveProperty('frequency');
    expect(exported.ribbonDecorations[0]).not.toHaveProperty('waveAmplitude');
    expect(exported.ribbonDecorations[0]).not.toHaveProperty('waveFrequency');
    expect(exported.lifecycle.durationSeconds).toBeGreaterThan(0);
    expect(exported.samplingPolicy.historyFps).toBe(60);
  });

  it('serializes game export presets deterministically', () => {
    expect(serializeGameExportPreset(createDefaultPreset())).toBe(serializeGameExportPreset(createDefaultPreset()));
  });
});

function allKeys(value: unknown): string[] {
  if (!value || typeof value !== 'object') {
    return [];
  }
  if (Array.isArray(value)) {
    return value.flatMap(allKeys);
  }
  return Object.entries(value).flatMap(([key, child]) => [key, ...allKeys(child)]);
}
