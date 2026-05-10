import { describe, expect, it } from 'vitest';
import { createDefaultPreset } from '../model/preset';
import { serializeGameExportPreset, toGameExportPreset } from './gameExport';

describe('game projectile vfx export boundary', () => {
  it('omits preview-only fields from the game export preset', () => {
    const exported = toGameExportPreset(createDefaultPreset());
    const json = JSON.stringify(exported);

    expect(exported).toHaveProperty('name');
    expect(exported).toHaveProperty('trailEntities');
    expect(exported).toHaveProperty('hooks');
    expect(exported).toHaveProperty('lifecycle');

    expect(json).not.toContain('timeline');
    expect(json).not.toContain('simulation');
    expect(json).not.toContain('previewCamera');
    expect(json).not.toContain('projectileVelocity');
    expect(json).not.toContain('curve');
    expect(json).not.toContain('loop');
  });

  it('serializes game export presets deterministically', () => {
    expect(serializeGameExportPreset(createDefaultPreset())).toBe(serializeGameExportPreset(createDefaultPreset()));
  });
});
