import { render, screen } from '@testing-library/react';
import App from './App';

describe('App', () => {
  it('renders the generic VFX preview workbench with MD3 controls', () => {
    render(<App />);

    expect(screen.getByRole('heading', { name: 'Game VFX Preview' })).toBeInTheDocument();
    expect(screen.getByLabelText('Effect preview viewport')).toBeInTheDocument();
    expect(document.querySelector('md-outlined-select')).toBeTruthy();
    expect(document.querySelector('md-switch[aria-label="Play animation"]')).toBeTruthy();
    expect(document.querySelector('md-slider[aria-label="Preview timeline"]')).toBeTruthy();
    expect(document.querySelectorAll('md-slider').length).toBeGreaterThanOrEqual(10);
    expect(screen.getByText('Frame Control')).toBeInTheDocument();
    expect(screen.getByText('Shader Parameters')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Rotating Blue Starburst' })).toBeInTheDocument();
    expect(screen.getByText('Lens flare')).toBeInTheDocument();
  });
});
