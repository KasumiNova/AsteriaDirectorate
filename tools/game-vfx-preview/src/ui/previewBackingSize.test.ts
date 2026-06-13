import { previewBackingSize } from './previewBackingSize';

describe('previewBackingSize', () => {
  it('uses the real high-DPI device pixel ratio instead of capping normal retina displays', () => {
    expect(previewBackingSize(780, 703.328125, 2)).toEqual({
      width: 1560,
      height: 1407,
      ratio: 2,
    });
  });

  it('caps extreme device pixel ratios to keep the preview bounded', () => {
    expect(previewBackingSize(500, 300, 4)).toEqual({
      width: 1500,
      height: 900,
      ratio: 3,
    });
  });
});
