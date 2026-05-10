import { render, screen } from '@testing-library/react';
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
});
