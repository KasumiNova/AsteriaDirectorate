import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { createDefaultPreset } from '../model/preset';
import { ConfigPanel } from './ConfigPanel';
import { EntityInspector } from './EntityInspector';

describe('projectile VFX export boundary labels', () => {
  it('marks preview-only controls and game export controls', () => {
    const preset = createDefaultPreset();

    render(
      <>
        <EntityInspector preset={preset} onPresetChange={vi.fn()} />
        <ConfigPanel preset={preset} onPresetChange={vi.fn()} />
      </>,
    );

    expect(screen.getAllByText(/Preview Only/i).length).toBeGreaterThan(0);
    expect(screen.getByText(/Game Export/i)).toBeTruthy();
    expect(screen.getByText(/export.*Game Export/i)).toBeTruthy();
  });
});
