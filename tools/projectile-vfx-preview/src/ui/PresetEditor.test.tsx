import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { createDefaultPreset } from '../model/preset';
import { ConfigPanel } from './ConfigPanel';
import { EntityInspector } from './EntityInspector';

describe('projectile VFX export boundary labels', () => {
  it('marks preview-only controls and kotlin component export controls', () => {
    const preset = createDefaultPreset();

    render(
      <>
        <EntityInspector preset={preset} onPresetChange={vi.fn()} />
        <ConfigPanel preset={preset} onPresetChange={vi.fn()} />
      </>,
    );

    expect(screen.getAllByText(/Preview Only/i).length).toBeGreaterThan(0);
    expect(screen.getAllByText(/Preview JSON/i).length).toBeGreaterThan(0);
    expect(screen.getByRole('button', { name: /Export Kotlin Component Preset/i })).toBeTruthy();
  });
});
