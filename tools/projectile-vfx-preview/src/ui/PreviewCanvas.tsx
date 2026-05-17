import { useEffect, useRef, useState, type PointerEvent, type WheelEvent } from 'react';
import { BoxUtilPreviewPreset } from '../model/preset';
import { createPreviewOverlayRenderer, PreviewOverlayLayerVisibility, PreviewOverlayRenderer } from '../render/previewOverlayRenderer';
import { WebGLTrailRenderer, createWebGLTrailRenderer } from '../render/webglTrailRenderer';

interface PanOffset {
  x: number;
  y: number;
}

interface DragState {
  pointerId: number;
  startX: number;
  startY: number;
  originX: number;
  originY: number;
}

export interface PreviewCanvasProps {
  preset: BoxUtilPreviewPreset;
  timeSeconds: number;
  onCanvasReady?: (canvas: HTMLCanvasElement | null) => void;
  layerVisibility?: Partial<PreviewOverlayLayerVisibility>;
}

export function PreviewCanvas({ preset, timeSeconds, onCanvasReady, layerVisibility }: PreviewCanvasProps) {
  const wrapRef = useRef<HTMLDivElement | null>(null);
  const trailCanvasRef = useRef<HTMLCanvasElement | null>(null);
  const overlayCanvasRef = useRef<HTMLCanvasElement | null>(null);
  const rendererRef = useRef<WebGLTrailRenderer | null>(null);
  const overlayRendererRef = useRef<PreviewOverlayRenderer | null>(null);
  const dragRef = useRef<DragState | null>(null);
  const [webglUnavailable, setWebglUnavailable] = useState(false);
  const [zoom, setZoom] = useState(1);
  const [pan, setPan] = useState<PanOffset>({ x: 0, y: 0 });

  useEffect(() => {
    const canvas = trailCanvasRef.current;
    const overlayCanvas = overlayCanvasRef.current;
    onCanvasReady?.(overlayCanvas ?? canvas);
    if (!canvas) {
      return;
    }

    const renderer = createWebGLTrailRenderer(canvas);
    rendererRef.current = renderer;
    setWebglUnavailable(renderer === null);
  }, [onCanvasReady]);

  useEffect(() => {
    const canvas = overlayCanvasRef.current;
    if (!canvas) {
      return;
    }

    overlayRendererRef.current = createPreviewOverlayRenderer(canvas);
  }, []);

  useEffect(() => {
    const canvas = trailCanvasRef.current;
    const renderer = rendererRef.current;
    const wrap = wrapRef.current;
    const overlayCanvas = overlayCanvasRef.current;
    const overlayRenderer = overlayRendererRef.current;
    if (!canvas || !renderer || !wrap) {
      return;
    }

    const rect = wrap.getBoundingClientRect();
    const width = Math.max(1, Math.floor(rect.width || wrap.clientWidth || 960));
    const height = Math.max(1, Math.floor(rect.height || wrap.clientHeight || 540));
    canvas.width = width;
    canvas.height = height;
    if (overlayCanvas && overlayRenderer) {
      overlayRenderer.resize(width, height);
    }
    renderer.clear(width, height);

    overlayRenderer?.render(preset, timeSeconds, layerVisibility);
  }, [preset, timeSeconds, layerVisibility]);

  const handleWheel = (event: WheelEvent<HTMLDivElement>) => {
    if (isInteractiveTarget(event.target)) {
      return;
    }
    const scaleDelta = event.deltaY < 0 ? 1.12 : 1 / 1.12;
    setZoom((current) => clamp(Number((current * scaleDelta).toFixed(3)), 0.5, 6));
  };

  const handlePointerDown = (event: PointerEvent<HTMLDivElement>) => {
    if (event.button !== 0 || isInteractiveTarget(event.target)) {
      return;
    }
    event.currentTarget.setPointerCapture(event.pointerId);
    dragRef.current = {
      pointerId: event.pointerId,
      startX: event.clientX,
      startY: event.clientY,
      originX: pan.x,
      originY: pan.y,
    };
  };

  const handlePointerMove = (event: PointerEvent<HTMLDivElement>) => {
    const drag = dragRef.current;
    if (!drag || drag.pointerId !== event.pointerId) {
      return;
    }
    setPan({ x: drag.originX + event.clientX - drag.startX, y: drag.originY + event.clientY - drag.startY });
  };

  const handlePointerUp = (event: PointerEvent<HTMLDivElement>) => {
    if (dragRef.current?.pointerId === event.pointerId) {
      dragRef.current = null;
    }
  };

  return (
    <div
      ref={wrapRef}
      className="preview-canvas-wrap"
      onWheel={handleWheel}
      onPointerDown={handlePointerDown}
      onPointerMove={handlePointerMove}
      onPointerUp={handlePointerUp}
      onPointerCancel={handlePointerUp}
    >
      <div className="preview-zoom-layer" style={{ transform: `translate(${pan.x}px, ${pan.y}px) scale(${zoom})` }}>
        <canvas ref={trailCanvasRef} className="preview-canvas preview-canvas-base" aria-hidden="true" />
        <canvas ref={overlayCanvasRef} className="preview-canvas preview-canvas-overlay" aria-label="Projectile VFX preview canvas" />
      </div>
      <label className="zoom-control">
        Zoom
        <input type="range" min="0.5" max="6" step="0.01" value={zoom} onChange={(event) => setZoom(Number(event.currentTarget.value))} />
        <span>{zoom.toFixed(2)}×</span>
      </label>
      {webglUnavailable && <div className="preview-fallback">WebGL unavailable. Preview renderer cannot start in this environment.</div>}
    </div>
  );
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}

function isInteractiveTarget(target: EventTarget): boolean {
  return target instanceof Element && target.closest('button, input, textarea, select, label') !== null;
}
