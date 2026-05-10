import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { createDefaultPreset } from '../model/preset';
import { VersionCompare } from './VersionCompare';

describe('VersionCompare', () => {
  it('saves and restores named preset snapshots', async () => {
    const user = userEvent.setup();
    const onPresetChange = vi.fn();
    const preset = createDefaultPreset();
    render(<VersionCompare preset={preset} onPresetChange={onPresetChange} />);

    await user.clear(screen.getByLabelText('Version name'));
    await user.type(screen.getByLabelText('Version name'), 'baseline');
    await user.click(screen.getByRole('button', { name: 'Save Version' }));
    await user.click(screen.getByRole('button', { name: 'Restore baseline' }));

    expect(onPresetChange).toHaveBeenCalledWith(expect.objectContaining({ name: preset.name }));
  });
});
