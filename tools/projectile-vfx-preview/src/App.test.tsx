import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import App from './App';

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
});
