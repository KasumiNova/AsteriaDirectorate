import { render, screen } from '@testing-library/react';
import { fireEvent } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { createDefaultPreset } from '../model/preset';
import { ConfigPanel } from './ConfigPanel';

describe('ConfigPanel', () => {
  it('accepts JSON and applies valid presets', async () => {
    const user = userEvent.setup();
    const onPresetChange = vi.fn();
    const preset = createDefaultPreset();

    render(<ConfigPanel preset={preset} onPresetChange={onPresetChange} />);
    const textarea = screen.getByLabelText('Import JSON');
    fireEvent.change(textarea, { target: { value: JSON.stringify({ ...preset, name: 'Imported preset' }) } });
    await user.click(screen.getByRole('button', { name: 'Apply JSON' }));

    expect(onPresetChange).toHaveBeenCalledWith(expect.objectContaining({ name: 'Imported preset' }));
    expect(screen.getByText('Preset applied.')).toBeInTheDocument();
  });

  it('shows parse errors for invalid JSON', async () => {
    const user = userEvent.setup();

    render(<ConfigPanel preset={createDefaultPreset()} onPresetChange={vi.fn()} />);
    fireEvent.change(screen.getByLabelText('Import JSON'), { target: { value: '{ bad' } });
    await user.click(screen.getByRole('button', { name: 'Apply JSON' }));

    expect(screen.getByText(/\$/)).toBeInTheDocument();
  });

  it('exports formatted JSON into the textarea', async () => {
    const user = userEvent.setup();

    render(<ConfigPanel preset={createDefaultPreset()} onPresetChange={vi.fn()} />);
    await user.click(screen.getByRole('button', { name: 'Export JSON' }));

    const exported = (screen.getByLabelText('Game Export') as HTMLTextAreaElement).value;
    expect(exported).toContain('"id": "aod7_shot"');
    expect(exported).toContain('"trailEntities"');
    expect(exported).not.toContain('"timeline"');
    expect(exported).not.toContain('"simulation"');
    expect(exported).not.toContain('"previewCamera"');
  });

  it('exports Kotlin preset code', async () => {
    const user = userEvent.setup();

    render(<ConfigPanel preset={createDefaultPreset()} onPresetChange={vi.fn()} />);
    await user.click(screen.getByRole('button', { name: 'Export Kotlin' }));

    expect((screen.getByLabelText('Game Export') as HTMLTextAreaElement).value).toContain('ASTDProjectileVfxPreset');
  });

  it('updates preview-only layer toggles without changing runtime export fields', async () => {
    const user = userEvent.setup();
    const onLayerVisibilityChange = vi.fn();
    const preset = createDefaultPreset();

    render(<ConfigPanel preset={preset} onPresetChange={vi.fn()} onLayerVisibilityChange={onLayerVisibilityChange} />);
    await user.click(screen.getByLabelText('toggle-head'));

    expect(onLayerVisibilityChange).toHaveBeenCalledWith(expect.objectContaining({ head: false }));
    expect((screen.getByLabelText('Game Export') as HTMLTextAreaElement).value).toContain('"headLayers"');
  });
});
