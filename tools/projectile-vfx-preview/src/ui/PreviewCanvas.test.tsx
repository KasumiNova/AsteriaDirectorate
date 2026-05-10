import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { createDefaultPreset } from '../model/preset';
import { PreviewCanvas } from './PreviewCanvas';

describe('PreviewCanvas', () => {
  it('renders an accessible canvas', () => {
    render(<PreviewCanvas preset={createDefaultPreset()} timeSeconds={0} />);

    expect(screen.getByLabelText('Projectile VFX preview canvas')).toBeInTheDocument();
  });

  it('displays fallback text when WebGL is unavailable', () => {
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(null);

    render(<PreviewCanvas preset={createDefaultPreset()} timeSeconds={0} />);

    expect(screen.getByText(/WebGL unavailable/i)).toBeInTheDocument();
  });
});
