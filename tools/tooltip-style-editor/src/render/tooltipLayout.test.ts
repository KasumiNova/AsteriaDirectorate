import { describe, expect, test } from 'vitest';
import { createDefaultHullmodTooltipPreset } from '../model/defaultHullmodPreset';
import { estimateTooltipLayout } from './tooltipLayout';

describe('estimateTooltipLayout', () => {
  test('estimates the default hullmod tooltip dimensions and all blocks', () => {
    const preset = createDefaultHullmodTooltipPreset();

    const layout = estimateTooltipLayout(preset);

    expect(layout.width).toBeGreaterThan(400);
    expect(layout.height).toBeGreaterThan(220);
    expect(layout.blocks).toHaveLength(preset.blocks.length);
    expect(layout.blocks.map((block) => block.id)).toEqual(preset.blocks.map((block) => block.id));
  });

  test('wraps long paragraph text into multiple deterministic lines', () => {
    const preset = createDefaultHullmodTooltipPreset();
    const longText =
      '这是一段用于测试确定性排版的长文本，包含多个中文字符以及 ASCII words and numbers 12345，用来确保 paragraph block 在固定宽度下会被折行到三行以上。';
    const paragraphIndex = preset.blocks.findIndex((block) => block.kind === 'paragraph');
    const targetBlock = preset.blocks[paragraphIndex];
    preset.blocks[paragraphIndex] = {
      ...targetBlock,
      text: longText,
    };

    const layout = estimateTooltipLayout(preset);
    const paragraphLayout = layout.blocks.find((block) => block.id === targetBlock.id);

    expect(paragraphLayout?.lineCount).toBeGreaterThan(2);
  });

  test('estimates table block height from header and row count', () => {
    const preset = createDefaultHullmodTooltipPreset();
    preset.blocks = [
      {
        id: 'table',
        kind: 'table',
        text: '',
        columns: [
          { id: 'a', label: 'A' },
          { id: 'b', label: 'B' },
        ],
        rows: [
          { id: 'r1', cells: [{ text: '1' }, { text: '2' }] },
          { id: 'r2', cells: [{ text: '3' }, { text: '4' }] },
        ],
      },
    ];

    const layout = estimateTooltipLayout(preset);

    expect(layout.blocks[0].lineCount).toBe(3);
    expect(layout.blocks[0].height).toBe(84);
  });
});
