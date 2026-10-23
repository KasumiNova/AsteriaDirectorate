import type { EffectDefinition, EffectParameters } from '../../model/effectDefinition';
import { linkProgram } from './glProgram';
import { FULLSCREEN_VERTEX_SHADER, GLSL_COMMON } from './shaderChunks';

export interface EffectRenderer {
  render(
    effect: EffectDefinition,
    parameters: EffectParameters,
    timeSeconds: number,
    width: number,
    height: number,
  ): void;
  dispose(): void;
}

interface CompiledEffect {
  program: WebGLProgram;
  positionLocation: number;
  uniforms: Map<string, WebGLUniformLocation | null>;
}

// 单个超大三角形覆盖视口，屏幕外部分由 GPU 裁剪。
export function createFullscreenTriangle(): Float32Array {
  return new Float32Array([
    -1, -1,
    3, -1,
    -1, 3,
  ]);
}

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

  gl.enable(gl.BLEND);
  gl.blendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA);

  const cache = new Map<string, CompiledEffect>();

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
