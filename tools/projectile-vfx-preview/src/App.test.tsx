import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';
import exportedAod7 from '../../../contents/data/config/astd_projectile_vfx_presets/aod7_shot.json';
import App from './App';
import { AOD7_PRESET_STORAGE_VERSION } from './model/aod7Preset';
import type { GameProjectileVfxPreset } from './model/gameExport';

beforeEach(() => {
  const store = new Map<string, string>();
  Object.defineProperty(globalThis, 'localStorage', {
    configurable: true,
    value: {
      getItem: (key: string) => store.get(key) ?? null,
      setItem: (key: string, value: string) => store.set(key, value),
      clear: () => store.clear(),
    },
  });
});

describe('App', () => {
  it('wires preview layout sections', () => {
    render(<App />);

    expect(screen.getByLabelText('Projectile VFX preview canvas')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Preset I\/O/ })).toBeInTheDocument();
    expect(screen.getByLabelText('Timeline controls')).toBeInTheDocument();
    expect(screen.getByText('TrailEntity')).toBeInTheDocument();
    expect(screen.getByText('ASTD Default TrailEntity Preview')).toBeInTheDocument();
  });

  it('switches trajectory preview mode from the stage controls', async () => {
    const user = userEvent.setup();
    render(<App />);

    const straight = screen.getByRole('button', { name: 'Straight trajectory preview' });
    const curved = screen.getByRole('button', { name: 'Curved trajectory preview' });

    expect(straight).toHaveAttribute('aria-pressed', 'true');
    expect(curved).toHaveAttribute('aria-pressed', 'false');

    await user.click(curved);

    expect(straight).toHaveAttribute('aria-pressed', 'false');
    expect(curved).toHaveAttribute('aria-pressed', 'true');
  });

  it('ignores stale cached presets so the editor opens on the checked-in AOD-7 baseline', () => {
    localStorage.setItem('astd-projectile-vfx-preset', JSON.stringify({ name: 'stale-local-cache', storageVersion: 'old' }));
    const exported = exportedAod7 as unknown as GameProjectileVfxPreset;

    render(<App />);

    expect(screen.getByText(exported.name)).toBeInTheDocument();
    expect(screen.queryByText('stale-local-cache')).not.toBeInTheDocument();
  });

  it('restores cached presets only when the cache version matches the current AOD-7 baseline', () => {
    localStorage.setItem('astd-projectile-vfx-preset', JSON.stringify({
      name: 'current-local-cache',
      storageVersion: AOD7_PRESET_STORAGE_VERSION,
    }));

    render(<App />);

    expect(screen.getByText('current-local-cache')).toBeInTheDocument();
  });
});
