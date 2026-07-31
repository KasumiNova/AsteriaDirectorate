import { describe, expect, test } from 'vitest';
import {
  TOOLTIP_BACKGROUND_SHADER_PRESETS,
  createDefaultHullmodTooltipPreset,
} from './defaultHullmodPreset';
import { normalizeTooltipPreset, type TooltipPresetPatch } from './tooltipPreset';

describe('tooltip preset model', () => {
  test('default preset recreates the screenshot hullmod structure', () => {
    const preset = createDefaultHullmodTooltipPreset();

    expect(preset.storageVersion).toBe('tooltip-style-editor/v3');
    expect(preset.kind).toBe('hullmod-tooltip');
    expect(preset.hullmod).toEqual({
      id: 'hullmod-tooltip',
      displayName: '幅能配送器',
      designType: '普通',
      tierLabel: '设计类型： 普通',
      iconLabel: '',
      opCost: 20,
    });
    expect(preset.theme.panel.width).toBeGreaterThan(400);
    expect(preset.theme.panel.minHeight).toBeGreaterThan(200);
    expect(preset.theme.text.designType).toEqual({ r: 106, g: 169, b: 255, a: 1 });
    expect(preset.theme.panel.backgroundColor.r).toBeLessThan(4);
    expect(preset.theme.panel.backgroundColor.g).toBeLessThan(8);
    expect(preset.theme.panel.backgroundColor.b).toBeLessThan(8);
    expect(preset.theme.section.backgroundColor.g).toBeGreaterThan(preset.theme.section.backgroundColor.r);
    expect(preset.theme.section.backgroundColor.g).toBeGreaterThan(preset.theme.section.backgroundColor.b);
    expect(preset.theme.text.warning.r).toBeGreaterThan(230);
    expect(preset.theme.text.warning.g).toBeGreaterThan(180);
    expect(preset.background.shaderId).toBeTruthy();
    expect(preset.background.fragmentShader).toContain('gl_FragColor');
    expect(preset.background.uniforms).toHaveProperty('u_time');

    expect(preset.blocks).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          id: expect.any(String),
          kind: 'section-heading',
          text: 'S-插件增益',
          align: 'center',
        }),
      ]),
    );
    expect(preset.blocks.every((block) => typeof block.id === 'string' && block.id.length > 0)).toBe(
      true,
    );

    expect(JSON.stringify(preset)).toContain('30 / 60 / 90 / 150');
    expect(JSON.stringify(preset)).toContain('10 / 20 / 30 / 50');
    expect(JSON.stringify(preset)).toContain('"colorRole":"warning"');
    expect(JSON.stringify(preset)).not.toContain('数据百科');
    expect(TOOLTIP_BACKGROUND_SHADER_PRESETS).toHaveLength(4);
    expect(TOOLTIP_BACKGROUND_SHADER_PRESETS.every((shader) => shader.fragmentShader.includes('gl_FragColor'))).toBe(true);
  });

  test('supports table blocks and custom highlight colors', () => {
    const partial = {
      blocks: [
        {
          id: 'custom-section',
          kind: 'section-heading',
          text: '装填容量',
          padTop: 10,
          align: 'center',
        },
        {
          id: 'capacity-table',
          kind: 'table',
          text: '',
          padTop: 8,
          columns: [
            { id: 'hull', label: '船船级别', align: 'center' },
            { id: 'slots', label: '小型导弹槽', align: 'center' },
            { id: 'capacity', label: '装填容量', align: 'center' },
          ],
          rows: [
            {
              id: 'frigate-2',
              cells: [
                { text: '护卫舰', colorRole: 'warning' },
                { text: '2+', colorRole: 'warning' },
                { text: '4', color: { r: 255, g: 230, b: 0, a: 1 } },
              ],
            },
          ],
        },
        {
          id: 'custom-color',
          kind: 'paragraph',
          text: '冷却时间上升至 10 秒。',
          highlights: [{ value: '10', color: { r: 255, g: 120, b: 48, a: 1 } }],
        },
      ],
    } satisfies TooltipPresetPatch;

    const normalized = normalizeTooltipPreset(partial);

    expect(normalized.blocks[1].kind).toBe('table');
    expect(normalized.blocks[1].columns?.[0].label).toBe('船船级别');
    expect(normalized.blocks[2].highlights?.[0]).toEqual({
      value: '10',
      color: { r: 255, g: 120, b: 48, a: 1 },
    });
  });

  test('normalization preserves defaults while accepting partial persisted data', () => {
    const partial = {
      hullmod: { displayName: '测试插件' },
      blocks: [
        {
          id: 'summary',
          kind: 'paragraph',
          text: '测试 <hl>42</hl>',
          highlights: ['42'],
          padTop: 12,
          align: 'start',
        },
      ],
    } satisfies TooltipPresetPatch;

    const normalized = normalizeTooltipPreset(partial);
    const defaults = createDefaultHullmodTooltipPreset();

    expect(normalized.storageVersion).toBe('tooltip-style-editor/v3');
    expect(normalized.kind).toBe('hullmod-tooltip');
    expect(normalized.hullmod.displayName).toBe('测试插件');
    expect(normalized.hullmod.opCost).toBe(defaults.hullmod.opCost);
    expect(normalized.theme.panel.width).toBeGreaterThan(400);
    expect(normalized.theme.panel.borderColor).toEqual(defaults.theme.panel.borderColor);
    expect(normalized.theme.text.title).toEqual(defaults.theme.text.title);
    expect(normalized.theme.section.textColor).toEqual(defaults.theme.section.textColor);
    expect(normalized.background.fragmentShader).toBe(defaults.background.fragmentShader);
    expect(normalized.blocks).toEqual([
      {
        id: 'summary',
        kind: 'paragraph',
        text: '测试 <hl>42</hl>',
        highlights: ['42'],
        padTop: 12,
        align: 'start',
      },
    ]);
  });
});
