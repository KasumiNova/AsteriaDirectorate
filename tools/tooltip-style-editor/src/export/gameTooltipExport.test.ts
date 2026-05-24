import { describe, expect, it } from 'vitest';
import { createDefaultHullmodTooltipPreset } from '../model/defaultHullmodPreset';
import { serializeGameTooltipExport } from './gameTooltipExport';
import { formatTooltipKotlinScaffold } from './kotlinTooltipExport';

describe('game tooltip export pipeline', () => {
  it('serializes a game tooltip export with schema, shader, and blocks', () => {
    const exported = serializeGameTooltipExport(createDefaultHullmodTooltipPreset());

    expect(exported).toContain('"kind": "hullmod-tooltip"');
    expect(exported).toContain('"fragmentShader"');
    expect(exported).toContain('S-插件增益');
  });

  it('formats a Kotlin scaffold for wiring the tooltip into Starsector', () => {
    const scaffold = formatTooltipKotlinScaffold(createDefaultHullmodTooltipPreset());

    expect(scaffold).toContain('TooltipMakerAPI');
    expect(scaffold).toContain('addPostDescriptionSection');
    expect(scaffold).toContain('ASTDShaderTooltipBackground');
  });
});
