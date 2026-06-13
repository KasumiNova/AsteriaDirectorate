import { useEffect, useRef } from 'react';
import type { EffectDefinition, EffectParameters } from '../model/effectDefinition';
import { createEffectRenderer, type EffectRenderer } from '../render/webgl/effectRenderer';
import { previewBackingSize } from './previewBackingSize';

interface PreviewViewportProps {
  /** 当前要渲染的特效定义。 */
  effect: EffectDefinition;
  /** 当前参数值。 */
  parameters: EffectParameters;
  /** 动画时间（秒）。 */
  elapsedSeconds: number;
  /** WebGL 初始化失败时的回调。 */
  onError?: (message: string) => void;
}

/**
 * 预览视口：在一个 canvas 上用通用特效渲染器持续绘制当前特效。
 *
 * 通过 ref 把最新的特效、参数与时间传入渲染循环，避免每次状态变化都重建
 * WebGL 上下文；渲染器内部会按特效 id 缓存已编译的 program。
 */
export function PreviewViewport({ effect, parameters, elapsedSeconds, onError }: PreviewViewportProps) {
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
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
