import { useEffect, useRef } from 'react';
import type { BeamPreset } from '../model/beamPreset';
import { renderBeamPreview, type BeamRenderSummary } from '../render/beamPreviewRenderer';

const PREVIEW_MAX_DEVICE_PIXEL_RATIO = 1;
const PREVIEW_MAX_BACKING_LONG_EDGE = 1280;
const PREVIEW_TARGET_FPS = 12;

interface BeamPreviewCanvasProps {
  preset: BeamPreset;
  onRender?: (summary: BeamRenderSummary) => void;
}

export function BeamPreviewCanvas({ preset, onRender }: BeamPreviewCanvasProps) {
  const canvasRef = useRef<HTMLCanvasElement | null>(null);

  useEffect(() => {
    let frame = 0;
    let start = performance.now();
    let lastDraw = 0;
    const minFrameMs = 1000 / PREVIEW_TARGET_FPS;

    const draw = (now: number) => {
      if (document.visibilityState === 'hidden') {
        frame = window.requestAnimationFrame(draw);
        return;
      }

      if (now - lastDraw < minFrameMs) {
        frame = window.requestAnimationFrame(draw);
        return;
      }

      const canvas = canvasRef.current;
      const ctx = canvas?.getContext('2d');
      if (!canvas || !ctx) {
        frame = window.requestAnimationFrame(draw);
        return;
      }

      const rect = canvas.getBoundingClientRect();
      const width = Math.max(1, Math.round(rect.width || 900));
      const height = Math.max(1, Math.round(rect.height || 480));
      const ratio = previewBackingRatio(width, height, window.devicePixelRatio || 1);
      const backingWidth = Math.round(width * ratio);
      const backingHeight = Math.round(height * ratio);
      if (canvas.width !== backingWidth || canvas.height !== backingHeight) {
        canvas.width = backingWidth;
        canvas.height = backingHeight;
      }

      ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
      const summary = renderBeamPreview(ctx, preset, {
        width,
        height,
        timeSeconds: (now - start) / 1000,
      });
      onRender?.(summary);
      lastDraw = now;
      frame = window.requestAnimationFrame(draw);
    };

    start = performance.now();
    frame = window.requestAnimationFrame(draw);
    return () => window.cancelAnimationFrame(frame);
  }, [onRender, preset]);

  return <canvas ref={canvasRef} className="beam-preview-canvas" aria-label="Beam VFX preview canvas" />;
}

export function previewBackingRatio(cssWidth: number, cssHeight: number, devicePixelRatio: number): number {
  const longEdge = Math.max(cssWidth, cssHeight, 1);
  const budgetRatio = PREVIEW_MAX_BACKING_LONG_EDGE / longEdge;
  return Math.max(0.25, Math.min(PREVIEW_MAX_DEVICE_PIXEL_RATIO, devicePixelRatio, budgetRatio));
}
