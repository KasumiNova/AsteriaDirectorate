import '@testing-library/jest-dom/vitest';
import { render, screen } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import { createDefaultHullmodTooltipPreset } from '../model/defaultHullmodPreset';
import { TooltipPreview } from './TooltipPreview';

describe('TooltipPreview', () => {
  test('renders the default hullmod screenshot text', () => {
    render(<TooltipPreview preset={createDefaultHullmodTooltipPreset()} />);

    expect(screen.getByText('幅能配送器')).toBeInTheDocument();
    expect(screen.getByText('S-插件增益')).toBeInTheDocument();
    expect(screen.queryByText('20')).not.toBeInTheDocument();
    expect(screen.queryByText('S')).not.toBeInTheDocument();
    expect(screen.queryByText(/数据百科/)).not.toBeInTheDocument();
  });

  test('renders table blocks and custom colored cells', () => {
    const preset = createDefaultHullmodTooltipPreset();
    preset.blocks = [
      {
        id: 'table',
        kind: 'table',
        text: '',
        columns: [
          { id: 'hull', label: '船船级别' },
          { id: 'slots', label: '小型导弹槽' },
          { id: 'capacity', label: '装填容量' },
        ],
        rows: [
          {
            id: 'row-a',
            cells: [
              { text: '护卫舰', colorRole: 'warning' },
              { text: '2+' },
              { text: '4', color: { r: 255, g: 224, b: 36, a: 1 } },
            ],
          },
        ],
      },
    ];

    render(<TooltipPreview preset={preset} />);

    expect(screen.getByText('船船级别')).toBeInTheDocument();
    expect(screen.getByText('小型导弹槽')).toBeInTheDocument();
    expect(screen.getByText('护卫舰')).toBeInTheDocument();
  });

  test('renders design type with fixed muted color and section block colors', () => {
    const preset = createDefaultHullmodTooltipPreset();
    preset.theme.text.designType = { r: 255, g: 224, b: 36, a: 1 };
    preset.hullmod.designTypeColor = { r: 255, g: 224, b: 36, a: 1 };
    preset.blocks = [
      {
        id: 'custom-section',
        kind: 'section-heading',
        text: '自定义段落',
        backgroundColor: { r: 12, g: 34, b: 56, a: 0.75 },
        textColor: { r: 210, g: 220, b: 230, a: 1 },
      },
    ];

    render(<TooltipPreview preset={preset} />);

    expect(screen.getByText('设计类型：')).toHaveStyle('color: rgba(118, 139, 139, 1)');
    expect(screen.getByText('普通')).toHaveStyle('color: rgba(255, 224, 36, 1)');
    expect(screen.getByText('自定义段落')).toHaveStyle('background-color: rgba(12, 34, 56, 0.75)');
    expect(screen.getByText('自定义段落')).toHaveStyle('color: rgba(210, 220, 230, 1)');
  });
});
