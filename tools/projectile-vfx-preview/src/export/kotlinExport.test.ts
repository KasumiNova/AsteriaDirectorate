import { describe, expect, it } from 'vitest';
import { createDefaultPreset } from '../model/preset';
import { formatPresetKotlin } from './kotlinExport';

describe('formatPresetKotlin', () => {
  it('exports game runtime preset code without preview-only fields', () => {
    const preset = createDefaultPreset();
    preset.ribbonDecorations[0].colorGradient = {
      enabled: true,
      stops: [
        { offset: 0, color: [0, 0, 1, 1] },
        { offset: 1, color: [1, 0, 0, 0.5] },
      ],
    };
    preset.trailEntities[0].ribbonDecorations[0].colorGradient = preset.ribbonDecorations[0].colorGradient;
    const kotlinText = formatPresetKotlin(preset);

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
    expect(kotlinText).toContain('ASTDProjectileVfxHeadLayerSpec');
    expect(kotlinText).toContain('ASTDProjectileVfxGlowLayerSpec');
    expect(kotlinText).toContain('ASTDProjectileVfxMistLayerSpec');
    expect(kotlinText).toContain('ASTDProjectileVfxSideWispLayerSpec');
    expect(kotlinText).toContain('ASTDProjectileVfxLifecycleSpec');
    expect(kotlinText).toContain('layoutReferenceWidth = 1846f');
    expect(kotlinText).toContain('ASTDProjectileVfxSamplingPolicy');
    expect(kotlinText).toContain('ASTDProjectileVfxAnchorMode.HeadLocked');
    expect(kotlinText).toContain('ASTDProjectileVfxOrientationMode.ProjectileVelocity');
    expect(kotlinText).toContain('ASTDColor(');
    expect(kotlinText).toContain('amplitude = 1.35f');
    expect(kotlinText).toContain('frequency = 1.1f');
    expect(kotlinText).toContain('ASTDTrailDecorationColorStopSpec');
    expect(kotlinText).not.toContain('waveAmplitude =');
    expect(kotlinText).not.toContain('waveFrequency =');
    expect(kotlinText).not.toContain('ASTDTrailDecorationGradientStopSpec');
  });
});
