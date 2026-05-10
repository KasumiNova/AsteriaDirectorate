import { describe, expect, it, vi } from 'vitest';
import { captureCanvasPng } from './capture';

describe('captureCanvasPng', () => {
  it('returns a PNG data URL', () => {
    const canvas = document.createElement('canvas');
    vi.spyOn(canvas, 'toDataURL').mockReturnValue('data:image/png;base64,abc');

    expect(captureCanvasPng(canvas)).toBe('data:image/png;base64,abc');
  });
});
