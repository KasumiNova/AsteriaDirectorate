import { BoxUtilPreviewPreset, createDefaultPreset } from '../model/preset';
import {
  DEFAULT_PREVIEW_OVERLAY_LAYER_VISIBILITY,
  PreviewOverlayLayerVisibility,
  PreviewOverlayRenderer,
  createPreviewOverlayRenderer,
} from '../render/previewOverlayRenderer';

export function captureCanvasPng(canvas: HTMLCanvasElement): string {
  return canvas.toDataURL('image/png');
}

export type ParityCaptureBackground = 'preview-default';

export interface ParityCaptureSpec {
  presetId: string;
  width: number;
  height: number;
  elapsedSeconds: number;
  background: ParityCaptureBackground;
  layerVisibility: PreviewOverlayLayerVisibility;
  outputPath: string;
}

export interface ParityCaptureResult {
  dataUrl: string;
  metadata: ParityCaptureSpec;
}

export interface ParityCaptureOptions {
  spec?: Partial<Omit<ParityCaptureSpec, 'layerVisibility'>> & {
    layerVisibility?: Partial<PreviewOverlayLayerVisibility>;
  };
  rendererFactory?: (canvas: HTMLCanvasElement) => PreviewOverlayRenderer | null;
}

export interface Aod7ParityCaptureOptions extends ParityCaptureOptions {
  presetFactory?: () => BoxUtilPreviewPreset;
}

export const AOD7_PARITY_CAPTURE_OUTPUT_PATH = 'docs/dev-docs/projectile-vfx-parity/captures/preview/aod7-all-layers-reference.png';

export const DEFAULT_AOD7_PARITY_CAPTURE_SPEC: ParityCaptureSpec = {
  presetId: 'aod7_shot',
  width: 1846,
  height: 1055,
  elapsedSeconds: 0.42,
  background: 'preview-default',
  layerVisibility: { ...DEFAULT_PREVIEW_OVERLAY_LAYER_VISIBILITY },
  outputPath: AOD7_PARITY_CAPTURE_OUTPUT_PATH,
};

export function createAod7ParityCaptureSpec(
  overrides: ParityCaptureOptions['spec'] = {},
): ParityCaptureSpec {
  return {
    ...DEFAULT_AOD7_PARITY_CAPTURE_SPEC,
    ...overrides,
    layerVisibility: {
      ...DEFAULT_AOD7_PARITY_CAPTURE_SPEC.layerVisibility,
      ...overrides.layerVisibility,
    },
  };
}

export function captureParityReference(
  canvas: HTMLCanvasElement,
  preset: BoxUtilPreviewPreset,
  options: ParityCaptureOptions = {},
): ParityCaptureResult {
  const metadata = createAod7ParityCaptureSpec(options.spec);
  const rendererFactory = options.rendererFactory ?? createPreviewOverlayRenderer;
  const renderer = rendererFactory(canvas);
  if (!renderer) {
    throw new Error('Preview overlay renderer is unavailable for parity capture');
  }

  renderer.resize(metadata.width, metadata.height);
  renderer.render(preset, metadata.elapsedSeconds, metadata.layerVisibility);

  return {
    dataUrl: captureCanvasPng(canvas),
    metadata,
  };
}

export function captureAod7ParityReference(
  canvas: HTMLCanvasElement,
  options: Aod7ParityCaptureOptions = {},
): ParityCaptureResult {
  const presetFactory = options.presetFactory ?? createDefaultPreset;
  return captureParityReference(canvas, presetFactory(), options);
}
