import '@testing-library/jest-dom/vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, test, vi } from 'vitest';
import { createDefaultHullmodTooltipPreset } from '../model/defaultHullmodPreset';
import { BlockEditor } from './BlockEditor';

describe('BlockEditor color controls', () => {
  test('uses picker-first color controls instead of hex-only text fields', () => {
    render(
      <BlockEditor
        preset={createDefaultHullmodTooltipPreset()}
        onPresetChange={() => undefined}
      />,
    );

    expect(screen.getAllByRole('button', { name: /Text color/i })[0]).toBeInTheDocument();
    expect(screen.queryByRole('textbox', { name: /Text color custom color/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Design type color/i })).not.toBeInTheDocument();
  });
});

describe('BlockEditor content operations', () => {
  test('adds a table column with cells for every row', async () => {
    const user = userEvent.setup();
    const preset = createDefaultHullmodTooltipPreset();
    preset.blocks = [
      {
        id: 'table',
        kind: 'table',
        text: '',
        columns: [{ id: 'col-a', label: '项目' }],
        rows: [
          { id: 'row-a', cells: [{ text: '护卫舰' }] },
          { id: 'row-b', cells: [{ text: '巡洋舰' }] },
        ],
      },
    ];
    const onPresetChange = vi.fn();

    render(<BlockEditor preset={preset} onPresetChange={onPresetChange} />);
    await user.click(screen.getByRole('button', { name: /Add column/i }));

    const next = onPresetChange.mock.calls[0][0];
    expect(next.blocks[0].columns).toHaveLength(2);
    expect(next.blocks[0].rows.every((row: { cells: unknown[] }) => row.cells.length === 2)).toBe(true);
  });

  test('removes a table column and matching cells', async () => {
    const user = userEvent.setup();
    const preset = createDefaultHullmodTooltipPreset();
    preset.blocks = [
      {
        id: 'table',
        kind: 'table',
        text: '',
        columns: [
          { id: 'col-a', label: '项目' },
          { id: 'col-b', label: '数值' },
        ],
        rows: [
          { id: 'row-a', cells: [{ text: '护卫舰' }, { text: '2+' }] },
          { id: 'row-b', cells: [{ text: '巡洋舰' }, { text: '4+' }] },
        ],
      },
    ];
    const onPresetChange = vi.fn();

    render(<BlockEditor preset={preset} onPresetChange={onPresetChange} />);
    await user.click(screen.getByRole('button', { name: /Delete column 1/i }));

    const next = onPresetChange.mock.calls[0][0];
    expect(next.blocks[0].columns).toEqual([{ id: 'col-b', label: '数值' }]);
    expect(next.blocks[0].rows.map((row: { cells: Array<{ text: string }> }) => row.cells)).toEqual([
      [{ text: '2+' }],
      [{ text: '4+' }],
    ]);
  });

  test('removes a table row', async () => {
    const user = userEvent.setup();
    const preset = createDefaultHullmodTooltipPreset();
    preset.blocks = [
      {
        id: 'table',
        kind: 'table',
        text: '',
        columns: [{ id: 'col-a', label: '项目' }],
        rows: [
          { id: 'row-a', cells: [{ text: '护卫舰' }] },
          { id: 'row-b', cells: [{ text: '巡洋舰' }] },
        ],
      },
    ];
    const onPresetChange = vi.fn();

    render(<BlockEditor preset={preset} onPresetChange={onPresetChange} />);
    await user.click(screen.getByRole('button', { name: /Delete row 1/i }));

    const next = onPresetChange.mock.calls[0][0];
    expect(next.blocks[0].rows).toEqual([{ id: 'row-b', cells: [{ text: '巡洋舰' }] }]);
  });

  test('reorders table columns by dragging a column handle', () => {
    const preset = createDefaultHullmodTooltipPreset();
    preset.blocks = [
      {
        id: 'table',
        kind: 'table',
        text: '',
        columns: [
          { id: 'col-a', label: '项目' },
          { id: 'col-b', label: '数值' },
        ],
        rows: [
          { id: 'row-a', cells: [{ text: '护卫舰' }, { text: '2+' }] },
        ],
      },
    ];
    const onPresetChange = vi.fn();

    render(<BlockEditor preset={preset} onPresetChange={onPresetChange} />);
    const firstColumn = screen.getByLabelText('Drag column 1');
    const secondColumn = screen.getByLabelText('Drop before column 2');

    fireEvent.dragStart(firstColumn);
    fireEvent.dragOver(secondColumn);
    fireEvent.drop(secondColumn);

    const next = onPresetChange.mock.calls[0][0];
    expect(next.blocks[0].columns.map((column: { id: string }) => column.id)).toEqual(['col-b', 'col-a']);
    expect(next.blocks[0].rows[0].cells.map((cell: { text: string }) => cell.text)).toEqual(['2+', '护卫舰']);
  });

  test('reorders table rows by dragging a row handle', () => {
    const preset = createDefaultHullmodTooltipPreset();
    preset.blocks = [
      {
        id: 'table',
        kind: 'table',
        text: '',
        columns: [{ id: 'col-a', label: '项目' }],
        rows: [
          { id: 'row-a', cells: [{ text: '护卫舰' }] },
          { id: 'row-b', cells: [{ text: '巡洋舰' }] },
        ],
      },
    ];
    const onPresetChange = vi.fn();

    render(<BlockEditor preset={preset} onPresetChange={onPresetChange} />);
    const firstRow = screen.getByLabelText('Drag row 1');
    const secondRow = screen.getByLabelText('Drop before row 2');

    fireEvent.dragStart(firstRow);
    fireEvent.dragOver(secondRow);
    fireEvent.drop(secondRow);

    const next = onPresetChange.mock.calls[0][0];
    expect(next.blocks[0].rows.map((row: { id: string }) => row.id)).toEqual(['row-b', 'row-a']);
  });

  test('removes a colored span', async () => {
    const user = userEvent.setup();
    const preset = createDefaultHullmodTooltipPreset();
    const onPresetChange = vi.fn();

    render(<BlockEditor preset={preset} onPresetChange={onPresetChange} />);
    await user.click(screen.getAllByRole('button', { name: /Delete colored span/i })[0]);

    const next = onPresetChange.mock.calls[0][0];
    expect(next.blocks[0].highlights).toHaveLength(0);
  });

  test('reorders blocks by dragging one block onto another', () => {
    const preset = createDefaultHullmodTooltipPreset();
    const onPresetChange = vi.fn();

    render(<BlockEditor preset={preset} onPresetChange={onPresetChange} />);
    const firstBlock = screen.getByTestId('tooltip-block-card-summary');
    const secondBlock = screen.getByTestId('tooltip-block-card-s-mod-heading');

    const handle = within(firstBlock).getByLabelText('Drag paragraph');

    fireEvent.dragStart(handle);
    fireEvent.dragOver(secondBlock);
    fireEvent.drop(secondBlock);

    const next = onPresetChange.mock.calls[0][0];
    expect(next.blocks[0].id).toBe('s-mod-heading');
    expect(next.blocks[1].id).toBe('summary');
  });

  test('does not start block dragging from editable body controls', () => {
    const preset = createDefaultHullmodTooltipPreset();
    const onPresetChange = vi.fn();

    render(<BlockEditor preset={preset} onPresetChange={onPresetChange} />);
    const firstBlock = screen.getByTestId('tooltip-block-card-summary');
    const secondBlock = screen.getByTestId('tooltip-block-card-s-mod-heading');

    fireEvent.dragStart(screen.getAllByRole('textbox', { name: 'Text' })[0]);
    fireEvent.dragOver(secondBlock);
    fireEvent.drop(secondBlock);

    expect(onPresetChange).not.toHaveBeenCalled();
    expect(firstBlock).not.toHaveClass('content-block-card--dragging');
  });

  test('keeps compact color rows on one line', () => {
    const preset = createDefaultHullmodTooltipPreset();

    render(<BlockEditor preset={preset} onPresetChange={() => undefined} />);
    const row = screen.getAllByText('Text color')[0].closest('.swatch-row');

    expect(row).toHaveClass('swatch-row--compact');
    expect(within(row as HTMLElement).getByRole('button', { name: /Text color/i })).toBeInTheDocument();
  });

  test('edits section heading background and text colors per block', async () => {
    const user = userEvent.setup();
    const preset = createDefaultHullmodTooltipPreset();
    const onPresetChange = vi.fn();

    render(<BlockEditor preset={preset} onPresetChange={onPresetChange} />);
    await user.click(screen.getByRole('button', { name: /Heading bg/i }));
    await user.click(screen.getByRole('button', { name: /Heading bg preset Orange/i }));

    const next = onPresetChange.mock.calls[0][0];
    const heading = next.blocks.find((block: { id: string }) => block.id === 's-mod-heading');
    expect(heading.backgroundColor).toEqual({ r: 255, g: 148, b: 42, a: 1 });
  });

  test('edits section heading alpha values', async () => {
    const user = userEvent.setup();
    const preset = createDefaultHullmodTooltipPreset();
    const onPresetChange = vi.fn();

    render(<BlockEditor preset={preset} onPresetChange={onPresetChange} />);
    await user.click(screen.getByRole('button', { name: /Heading bg/i }));
    fireEvent.change(screen.getByRole('slider', { name: /Heading bg Alpha/i }), { target: { value: '0.5' } });

    const next = onPresetChange.mock.calls[0][0];
    const heading = next.blocks.find((block: { id: string }) => block.id === 's-mod-heading');
    expect(heading.backgroundColor.a).toBe(0.5);
  });

  test('edits custom design type text color separately from the fixed label color', async () => {
    const user = userEvent.setup();
    const preset = createDefaultHullmodTooltipPreset();
    const onPresetChange = vi.fn();

    render(<BlockEditor preset={preset} onPresetChange={onPresetChange} />);
    await user.click(screen.getByRole('button', { name: /Design value color/i }));
    await user.click(screen.getByRole('button', { name: /Design value color preset Yellow/i }));

    const next = onPresetChange.mock.calls[0][0];
    expect(next.hullmod.designTypeColor).toEqual({ r: 255, g: 224, b: 36, a: 1 });
  });
});
