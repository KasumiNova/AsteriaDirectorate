import { describe, expect, it } from 'vitest';
import { createDefaultPreset } from './preset';
import { formatPresetJson, parsePresetJson } from './parsePreset';

describe('parsePresetJson', () => {
  it('parses valid JSON into a preset', () => {
    const result = parsePresetJson(JSON.stringify(createDefaultPreset()));

    expect(result.ok).toBe(true);
    if (result.ok) {
      expect(result.preset.trailEntities[0].id).toBe('astd_default_trail');
    }
  });

  it('returns errors for malformed JSON', () => {
    const result = parsePresetJson('{ bad json');

    expect(result.ok).toBe(false);
    if (!result.ok) {
      expect(result.errors[0].path).toBe('$');
    }
  });

  it('reports invalid RGBA paths', () => {
    const preset = createDefaultPreset();
    preset.trailEntities[0].startColor = [1, 2, 3] as never;

    const result = parsePresetJson(JSON.stringify(preset));

    expect(result.ok).toBe(false);
    if (!result.ok) {
      expect(result.errors.map((error) => error.path)).toContain('trailEntities[0].startColor');
    }
  });

  it('reports invalid trail width paths', () => {
    const preset = createDefaultPreset();
    preset.trailEntities[0].startWidth = Number.NaN;

    const result = parsePresetJson(JSON.stringify(preset));

    expect(result.ok).toBe(false);
    if (!result.ok) {
      expect(result.errors.map((error) => error.path)).toContain('trailEntities[0].startWidth');
    }
  });

  it('merges partial render graph fields from imported JSON', () => {
    const preset = createDefaultPreset();
    const result = parsePresetJson(JSON.stringify({
      ...preset,
      headLayers: [{ id: 'head-only-length', length: 260 }],
      glowLayers: [{ id: 'glow-only-width', widthScale: 9 }],
      mistLayers: [{ id: 'mist-only-range', rxRange: { max: 12 } }],
      sideWispLayers: [{ id: 'side-only-offsets', offsets: [-4, 4] }],
      lifecycle: { projectileHeadSizeScale: 2.5 },
      samplingPolicy: { maxHistoryNodes: 160 },
      ribbonDecorations: [{ id: 'ribbon-only-alpha', alphaScale: 0.77 }],
    }));

    expect(result.ok).toBe(true);
    if (result.ok) {
      expect(result.preset.headLayers[0]).toEqual(expect.objectContaining({ id: 'head-only-length', length: 260, width: preset.headLayers[0].width }));
      expect(result.preset.glowLayers[0]).toEqual(expect.objectContaining({ id: 'glow-only-width', widthScale: 9, alphaScale: preset.glowLayers[0].alphaScale }));
      expect(result.preset.mistLayers[0].rxRange).toEqual(expect.objectContaining({ min: preset.mistLayers[0].rxRange.min, max: 12 }));
      expect(result.preset.sideWispLayers[0]).toEqual(expect.objectContaining({ id: 'side-only-offsets', offsets: [-4, 4], widthScale: preset.sideWispLayers[0].widthScale }));
      expect(result.preset.lifecycle).toEqual(expect.objectContaining({ projectileHeadSizeScale: 2.5, durationSeconds: preset.lifecycle.durationSeconds }));
      expect(result.preset.samplingPolicy).toEqual(expect.objectContaining({ maxHistoryNodes: 160, historyFps: preset.samplingPolicy.historyFps }));
      expect(result.preset.ribbonDecorations[0]).toEqual(expect.objectContaining({ id: 'ribbon-only-alpha', alphaScale: 0.77, thickness: preset.ribbonDecorations[0].thickness }));
    }
  });
});

describe('formatPresetJson', () => {
  it('formats stable JSON', () => {
    const text = formatPresetJson(createDefaultPreset());

    expect(text).toContain('"trailEntities"');
    expect(text.endsWith('\n')).toBe(true);
  });
});
