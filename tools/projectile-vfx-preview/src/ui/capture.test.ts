import { describe, expect, it, vi } from 'vitest';
import { createDefaultPreset } from '../model/preset';
import {
  AOD7_PARITY_CAPTURE_OUTPUT_PATH,
  DEFAULT_AOD7_PARITY_CAPTURE_SPEC,
  captureAod7ParityReference,
  captureCanvasPng,
  captureParityReference,
  createAod7ParityCaptureSpec,
} from './capture';

describe('captureCanvasPng', () => {
  it('returns a PNG data URL', () => {
    const canvas = document.createElement('canvas');
    vi.spyOn(canvas, 'toDataURL').mockReturnValue('data:image/png;base64,abc');

    expect(captureCanvasPng(canvas)).toBe('data:image/png;base64,abc');
  });
});

describe('deterministic parity capture', () => {
  it('pins the AOD-7 reference capture contract', () => {
    expect(DEFAULT_AOD7_PARITY_CAPTURE_SPEC).toEqual({
      presetId: 'aod7_shot',
      width: 1846,
      height: 1055,
      elapsedSeconds: 0.42,
      background: 'preview-default',
      layerVisibility: {
        trail: true,
        head: true,
        glow: true,
        mist: true,
        sideWisps: true,
        ribbon: true,
      },
      outputPath: AOD7_PARITY_CAPTURE_OUTPUT_PATH,
    });
  });

  it('allows deterministic capture overrides without changing the default contract', () => {
    const spec = createAod7ParityCaptureSpec({
      width: 960,
      layerVisibility: { mist: false },
      outputPath: 'tmp/reference.png',
    });

    expect(spec.width).toBe(960);
    expect(spec.height).toBe(1055);
    expect(spec.layerVisibility.mist).toBe(false);
    expect(spec.layerVisibility.head).toBe(true);
    expect(spec.outputPath).toBe('tmp/reference.png');
    expect(DEFAULT_AOD7_PARITY_CAPTURE_SPEC.layerVisibility.mist).toBe(true);
  });

  it('renders a fixed-size reference frame and returns metadata for verification', () => {
    const canvas = document.createElement('canvas');
    vi.spyOn(canvas, 'toDataURL').mockReturnValue('data:image/png;base64,reference');
    const preset = createDefaultPreset();
    const renderer = {
      resize: vi.fn(),
      render: vi.fn(),
    };

    const result = captureParityReference(canvas, preset, {
      rendererFactory: () => renderer,
    });

    expect(renderer.resize).toHaveBeenCalledWith(1846, 1055);
    expect(renderer.render).toHaveBeenCalledWith(preset, 0.42, DEFAULT_AOD7_PARITY_CAPTURE_SPEC.layerVisibility);
    expect(result).toEqual({
      dataUrl: 'data:image/png;base64,reference',
      metadata: DEFAULT_AOD7_PARITY_CAPTURE_SPEC,
    });
  });

  it('uses a dedicated AOD-7 preset factory so capture metadata cannot drift from rendered content', () => {
    const canvas = document.createElement('canvas');
    vi.spyOn(canvas, 'toDataURL').mockReturnValue('data:image/png;base64,aod7');
    const preset = createDefaultPreset();
    const presetFactory = vi.fn(() => preset);
    const renderer = {
      resize: vi.fn(),
      render: vi.fn(),
    };

    const result = captureAod7ParityReference(canvas, {
      presetFactory,
      rendererFactory: () => renderer,
    });

    expect(presetFactory).toHaveBeenCalledOnce();
    expect(renderer.render).toHaveBeenCalledWith(preset, 0.42, DEFAULT_AOD7_PARITY_CAPTURE_SPEC.layerVisibility);
    expect(result.metadata.presetId).toBe('aod7_shot');
  });
});
