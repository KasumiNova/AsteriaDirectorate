import { describe, expect, it } from 'vitest';
import { createDefaultBeamPreset } from '../model/beamPreset';
import { exportKotlinBeamDraftPreset } from './kotlinBeamExport';

describe('exportKotlinBeamDraftPreset', () => {
  it('exports draft Kotlin parameters without claiming runtime wiring', () => {
    const preset = {
      ...createDefaultBeamPreset(),
      mode: 'curved' as const,
      layers: [
        ...createDefaultBeamPreset().layers,
        {
          ...createDefaultBeamPreset().layers[0],
          id: 'halo',
          name: 'Halo',
          widthStart: 64,
          widthEnd: 24,
          bloomStrength: 0.8,
          noiseStrength: 0.2,
        },
      ],
    };

    const kotlin = exportKotlinBeamDraftPreset(preset);

    expect(kotlin).toContain('ASTDBeamVfxDraftPreset');
    expect(kotlin).toContain('mode = ASTDBeamVfxDraftMode.Curved');
    expect(kotlin).toContain('controlPoints = listOf');
    expect(kotlin).toContain('ASTDBeamVfxDraftLayer');
    expect(kotlin).toContain('noiseStrength = 0.2f');
    expect(kotlin).toContain('bloomStrength = 0.8f');
    expect(kotlin).not.toContain('ASTDProjectileVfxComponentSpec.Beam');
  });
});
