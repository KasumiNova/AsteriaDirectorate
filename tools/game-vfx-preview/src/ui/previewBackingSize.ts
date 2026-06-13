const MAX_PREVIEW_DEVICE_PIXEL_RATIO = 3;

export interface PreviewBackingSize {
  width: number;
  height: number;
  ratio: number;
}

export function previewBackingSize(
  cssWidth: number,
  cssHeight: number,
  devicePixelRatio: number,
): PreviewBackingSize {
  const ratio = Math.max(1, Math.min(MAX_PREVIEW_DEVICE_PIXEL_RATIO, devicePixelRatio || 1));
  return {
    width: Math.max(1, Math.round(cssWidth * ratio)),
    height: Math.max(1, Math.round(cssHeight * ratio)),
    ratio,
  };
}
