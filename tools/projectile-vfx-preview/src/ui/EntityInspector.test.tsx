import { render, screen } from '@testing-library/react';
import { fireEvent } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { createDefaultPreset } from '../model/preset';
import { EntityInspector } from './EntityInspector';

describe('EntityInspector', () => {
  it('displays TrailEntity group', () => {
    render(<EntityInspector preset={createDefaultPreset()} onPresetChange={vi.fn()} />);

    expect(screen.getByText('TrailEntity')).toBeInTheDocument();
  });

  it('edits key TrailEntity numeric fields', async () => {
    const onPresetChange = vi.fn();
    const preset = createDefaultPreset();
    const nextStartWidth = preset.trailEntities[0].startWidth + 1;
    render(<EntityInspector preset={preset} onPresetChange={onPresetChange} />);

    fireEvent.change(screen.getByLabelText('trail-startWidth'), { target: { value: String(nextStartWidth) } });

    expect(onPresetChange).toHaveBeenLastCalledWith(expect.objectContaining({
      trailEntities: [expect.objectContaining({ startWidth: nextStartWidth })],
    }));
  });

  it('edits texture and flick fields', async () => {
    const user = userEvent.setup();
    const onPresetChange = vi.fn();
    render(<EntityInspector preset={createDefaultPreset()} onPresetChange={onPresetChange} />);

    await user.clear(screen.getByLabelText('trail-textureSpeed'));
    await user.type(screen.getByLabelText('trail-textureSpeed'), '2');
    await user.clear(screen.getByLabelText('trail-uvOffset'));
    await user.type(screen.getByLabelText('trail-uvOffset'), '0.5');
    await user.clear(screen.getByLabelText('trail-flickMixValue'));
    await user.type(screen.getByLabelText('trail-flickMixValue'), '0.4');

    expect(onPresetChange).toHaveBeenCalled();
  });

  it('edits ribbon start and end colors', () => {
    const onPresetChange = vi.fn();
    render(<EntityInspector preset={createDefaultPreset()} onPresetChange={onPresetChange} />);

    fireEvent.change(screen.getByLabelText('trail-ribbon-0-startColor-0'), { target: { value: '128' } });
    fireEvent.change(screen.getByLabelText('trail-ribbon-0-endColor-3'), { target: { value: '64' } });

    expect(onPresetChange).toHaveBeenCalled();
  });

  it('keeps top-level ribbon graph in sync with ribbon panel edits', () => {
    const onPresetChange = vi.fn();
    render(<EntityInspector preset={createDefaultPreset()} onPresetChange={onPresetChange} />);

    fireEvent.change(screen.getByLabelText('trail-ribbon-0-alphaScale'), { target: { value: '0.77' } });

    expect(onPresetChange).toHaveBeenLastCalledWith(expect.objectContaining({
      ribbonDecorations: [expect.objectContaining({ alphaScale: 0.77 })],
      trailEntities: [expect.objectContaining({
        ribbonDecorations: [expect.objectContaining({ alphaScale: 0.77 })],
      })],
    }));
  });

  it('adds ribbon gradient stops', async () => {
    const user = userEvent.setup();
    const onPresetChange = vi.fn();
    render(<EntityInspector preset={createDefaultPreset()} onPresetChange={onPresetChange} />);

    await user.click(screen.getByRole('button', { name: 'Add Stop' }));

    expect(onPresetChange).toHaveBeenCalledWith(expect.objectContaining({
      trailEntities: [expect.objectContaining({
        ribbonDecorations: [expect.objectContaining({
          colorGradient: expect.objectContaining({
            stops: [expect.objectContaining({ offset: 1 })],
          }),
        })],
      })],
    }));
  });
});
