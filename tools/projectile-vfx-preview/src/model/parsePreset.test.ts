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
});

describe('formatPresetJson', () => {
  it('formats stable JSON', () => {
    const text = formatPresetJson(createDefaultPreset());

    expect(text).toContain('"trailEntities"');
    expect(text.endsWith('\n')).toBe(true);
  });
});
