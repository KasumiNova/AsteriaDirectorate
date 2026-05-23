import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { BeamEditor } from './BeamEditor';

describe('BeamEditor', () => {
  it('switches to curved mode', () => {
    render(<BeamEditor />);

    fireEvent.click(screen.getByRole('button', { name: 'Curved' }));

    expect(screen.getByLabelText('Beam mode')).toHaveTextContent('Curved');
  });

  it('adds and disables layers', () => {
    render(<BeamEditor />);

    fireEvent.click(screen.getByRole('button', { name: 'Add layer' }));
    expect(screen.getByTestId('layer-count')).toHaveTextContent('3 layers');

    fireEvent.click(screen.getAllByRole('checkbox', { name: /Enabled/ })[0]);
    expect(screen.getAllByRole('checkbox', { name: /Enabled/ })[0]).not.toBeChecked();
  });

  it('updates noise strength through layer controls', () => {
    render(<BeamEditor />);

    fireEvent.change(screen.getByLabelText('Noise strength'), { target: { value: '0.37' } });

    expect(screen.getByLabelText('Noise strength')).toHaveValue(0.37);
  });

  it('does not render disabled layers', () => {
    const draw = vi.fn();
    render(<BeamEditor renderPreview={draw} />);

    fireEvent.click(screen.getAllByRole('checkbox', { name: /Enabled/ })[0]);

    expect(draw).toHaveBeenLastCalledWith(expect.objectContaining({ enabledLayerCount: 1 }));
  });
});
