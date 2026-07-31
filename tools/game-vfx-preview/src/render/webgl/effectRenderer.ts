/**
 * 通用全屏特效渲染器。
 *
 * 把任意 {@link EffectDefinition} 渲染为一个全屏 fragment pass，遵循项目要求的
 * 「全屏 WebGL fragment pass」原则，同时与具体特效彻底解耦：
 *
 * - 共享一个全屏三角形顶点缓冲；
 * - 首次渲染某特效时编译其 program 并缓存（GLSL 公共头 + 特效片段）；
 * - 根据特效的 {@link ParameterSpec} 自动把参数上传为 `u_<key>` float uniform，
 *   无需为每个特效手写 uniform 绑定代码。
 *
 * 因此新增特效只需提供一份 {@link EffectDefinition}，渲染器无需任何改动。
 */
import type { EffectDefinition, EffectParameters } from '../../model/effectDefinition';
import { linkProgram } from './glProgram';
import { FULLSCREEN_VERTEX_SHADER, GLSL_COMMON } from './shaderChunks';

/** 渲染器对外接口：渲染一帧或释放 GPU 资源。 */
export interface EffectRenderer {
  /**
   * 渲染单帧。
   *
   * @param effect 要渲染的特效定义。
   * @param parameters 当前参数值。
   * @param timeSeconds 动画时间（秒）。
   * @param width 后备缓冲宽度（像素）。
   * @param height 后备缓冲高度（像素）。
   */
  render(
    effect: EffectDefinition,
    parameters: EffectParameters,
    timeSeconds: number,
    width: number,
    height: number,
  ): void;
  /** 释放所有缓存的 program 与顶点缓冲。 */
  dispose(): void;
}

/** 单个特效编译后的 GPU 资源与 uniform 位置缓存。 */
interface CompiledEffect {
  program: WebGLProgram;
  positionLocation: number;
  uniforms: Map<string, WebGLUniformLocation | null>;
}

/**
 * 覆盖整个裁剪空间的全屏三角形顶点。
 *
 * 单个超大三角形即可覆盖视口，比两三角形组成的四边形少一次顶点处理，
 * 屏幕外的部分会被裁剪掉。
 */
export function createFullscreenTriangle(): Float32Array {
  return new Float32Array([
    -1, -1,
    3, -1,
    -1, 3,
  ]);
}

/**
 * 创建绑定到指定 canvas 的通用特效渲染器。
 *
 * @param canvas 目标画布。
 * @returns 渲染器实例。
 * @throws WebGL 不可用或顶点缓冲创建失败时抛出。
 */
export function createEffectRenderer(canvas: HTMLCanvasElement): EffectRenderer {
  const context = canvas.getContext('webgl', {
    alpha: true,
    antialias: true,
    premultipliedAlpha: false,
  });

  if (!context) {
    throw new Error('WebGL is not available');
  }
  const gl: WebGLRenderingContext = context;

  const vertexBuffer = gl.createBuffer();
  if (!vertexBuffer) {
    throw new Error('Failed to create WebGL vertex buffer');
  }
  gl.bindBuffer(gl.ARRAY_BUFFER, vertexBuffer);
  gl.bufferData(gl.ARRAY_BUFFER, createFullscreenTriangle(), gl.STATIC_DRAW);

  // 普通 alpha 混合，让透明背景上的辉光自然叠加。
  gl.enable(gl.BLEND);
  gl.blendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA);

  const cache = new Map<string, CompiledEffect>();

  /** 编译单个特效并收集其 uniform 位置（含内建 u_time / u_resolution）。 */
  function compile(effect: EffectDefinition): CompiledEffect {
    const fragmentSource = `${GLSL_COMMON}\n${effect.fragmentShader}`;
    const program = linkProgram(gl, FULLSCREEN_VERTEX_SHADER, fragmentSource);
    const uniforms = new Map<string, WebGLUniformLocation | null>();
    uniforms.set('u_time', gl.getUniformLocation(program, 'u_time'));
    uniforms.set('u_resolution', gl.getUniformLocation(program, 'u_resolution'));
    for (const spec of effect.parameters) {
      const name = `u_${spec.key}`;
      uniforms.set(name, gl.getUniformLocation(program, name));
    }
    return {
      program,
      positionLocation: gl.getAttribLocation(program, 'a_position'),
      uniforms,
    };
  }

  /** 获取已缓存的编译结果，缺失则编译并缓存。 */
  function getCompiled(effect: EffectDefinition): CompiledEffect {
    let compiled = cache.get(effect.id);
    if (!compiled) {
      compiled = compile(effect);
      cache.set(effect.id, compiled);
    }
    return compiled;
  }

  return {
    render(effect, parameters, timeSeconds, width, height) {
      const compiled = getCompiled(effect);
      gl.viewport(0, 0, width, height);
      gl.clearColor(0, 0, 0, 0);
      gl.clear(gl.COLOR_BUFFER_BIT);
      gl.useProgram(compiled.program);

      gl.bindBuffer(gl.ARRAY_BUFFER, vertexBuffer);
      gl.enableVertexAttribArray(compiled.positionLocation);
      gl.vertexAttribPointer(compiled.positionLocation, 2, gl.FLOAT, false, 0, 0);

      const timeLocation = compiled.uniforms.get('u_time');
      if (timeLocation) {
        gl.uniform1f(timeLocation, timeSeconds);
      }
      const resolutionLocation = compiled.uniforms.get('u_resolution');
      if (resolutionLocation) {
        gl.uniform2f(resolutionLocation, width, height);
      }
      for (const spec of effect.parameters) {
        const location = compiled.uniforms.get(`u_${spec.key}`);
        if (location) {
          const value = parameters[spec.key];
          gl.uniform1f(location, Number.isFinite(value) ? value : spec.defaultValue);
        }
      }

      gl.drawArrays(gl.TRIANGLES, 0, 3);
    },
    dispose() {
      for (const compiled of cache.values()) {
        gl.deleteProgram(compiled.program);
      }
      cache.clear();
      gl.deleteBuffer(vertexBuffer);
    },
  };
}
