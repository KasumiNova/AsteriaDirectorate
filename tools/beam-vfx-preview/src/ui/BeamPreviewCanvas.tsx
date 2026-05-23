import { useEffect, useRef } from 'react';
import type { BeamPreset } from '../model/beamPreset';
import { renderBeamPreview, type BeamRenderSummary } from '../render/beamPreviewRenderer';

interface BeamPreviewCanvasProps {
  preset: BeamPreset;
  onRender?: (summary: BeamRenderSummary) => void;
}

export function BeamPreviewCanvas({ preset, onRender }: BeamPreviewCanvasProps) {
  const canvasRef = useRef<HTMLCanvasElement | null>(null);

  useEffect(() => {
    let frame = 0;
    let start = performance.now();

    const draw = (now: number) => {
      const canvas = canvasRef.current;
      const ctx = canvas?.getContext('2d');
      if (!canvas || !ctx) {
        return;
      }

      const rect = canvas.getBoundingClientRect();
      const width = Math.max(1, Math.round(rect.width || 900));
      const height = Math.max(1, Math.round(rect.height || 480));
      const ratio = window.devicePixelRatio || 1;
      if (canvas.width !== Math.round(width * ratio) || canvas.height !== Math.round(height * ratio)) {
        canvas.width = Math.round(width * ratio);
        canvas.height = Math.round(height * ratio);
      }

      ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
      const summary = renderBeamPreview(ctx, preset, {
        width,
        height,
        timeSeconds: (now - start) / 1000,
      });
      onRender?.(summary);
      frame = window.requestAnimationFrame(draw);
    };

    start = performance.now();
    frame = window.requestAnimationFrame(draw);
    return () => window.cancelAnimationFrame(frame);
  }, [onRender, preset]);

  return <canvas ref={canvasRef} className="beam-preview-canvas" aria-label="Beam VFX preview canvas" />;
}
