import { describe, expect, it } from 'vitest';
import { createDefaultPreset } from '../model/preset';
import { formatPresetKotlin } from './kotlinExport';

describe('formatPresetKotlin', () => {
  it('exports game runtime preset code without preview-only fields', () => {
    const kotlinText = formatPresetKotlin(createDefaultPreset());

    expect(kotlinText).not.toContain('TimelineConfig');
    expect(kotlinText).not.toContain('SimulationConfig');
    expect(kotlinText).not.toContain('PreviewCameraConfig');
    expect(kotlinText).not.toContain('projectileVelocity');
    expect(kotlinText).not.toContain('curve');
    expect(kotlinText).not.toContain('loop');

    expect(kotlinText).toContain('ASTDProjectileVfxPreset');
    expect(kotlinText).toContain('ASTDTrailEntitySpec');
    expect(kotlinText).toContain('ASTDTrailLayerSpec');
    expect(kotlinText).toContain('ASTDTrailRibbonDecorationSpec');
  });
});
