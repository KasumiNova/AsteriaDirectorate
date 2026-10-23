import { useEffect, useRef } from 'react';
import type { EffectDefinition, EffectParameters } from '../model/effectDefinition';
import { createEffectRenderer, type EffectRenderer } from '../render/webgl/effectRenderer';
import { previewBackingSize } from './previewBackingSize';

interface PreviewViewportProps {
  effect: EffectDefinition;
  parameters: EffectParameters;
  elapsedSeconds: number;
  onError?: (message: string) => void;
}

export function PreviewViewport({ effect, parameters, elapsedSeconds, onError }: PreviewViewportProps) {
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  // 渲染循环通过 ref 读取最新状态，避免状态变化时重建 WebGL 上下文。
  const effectRef = useRef(effect);
  const parametersRef = useRef(parameters);
  const elapsedRef = useRef(elapsedSeconds);

  useEffect(() => {
    effectRef.current = effect;
  }, [effect]);

  useEffect(() => {
    parametersRef.current = parameters;
  }, [parameters]);

  useEffect(() => {
    elapsedRef.current = elapsedSeconds;
  }, [elapsedSeconds]);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) {
      return;
    }

    let renderer: EffectRenderer;
    try {
      renderer = createEffectRenderer(canvas);
    } catch (error) {
      onError?.(error instanceof Error ? error.message : 'Failed to initialize WebGL preview');
      return;
    }

    let frame = 0;

    const draw = () => {
      const rect = canvas.getBoundingClientRect();
      const { width, height } = previewBackingSize(rect.width, rect.height, window.devicePixelRatio || 1);
      if (canvas.width !== width || canvas.height !== height) {
        canvas.width = width;
        canvas.height = height;
      }

      renderer.render(effectRef.current, parametersRef.current, elapsedRef.current, width, height);
      frame = requestAnimationFrame(draw);
    };

    frame = requestAnimationFrame(draw);
    return () => {
      cancelAnimationFrame(frame);
      renderer.dispose();
    };
  }, [onError]);

  return <canvas ref={canvasRef} className="preview-canvas" aria-label="Effect preview viewport" />;
}
