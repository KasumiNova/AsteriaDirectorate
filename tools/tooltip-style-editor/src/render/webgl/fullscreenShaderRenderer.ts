import { compileShaderProgram } from './shaderCompiler';

export type ShaderRenderParams = {
  timeSeconds: number;
  resolution: [number, number];
  primaryColor: [number, number, number, number];
  accentColor: [number, number, number, number];
  intensity: number;
};

type ShaderUniformLocations = {
  time: WebGLUniformLocation | null;
  resolution: WebGLUniformLocation | null;
  primaryColor: WebGLUniformLocation | null;
  accentColor: WebGLUniformLocation | null;
  intensity: WebGLUniformLocation | null;
};

export class FullscreenShaderRenderer {
  private readonly gl: WebGLRenderingContext;
  private readonly vertexBuffer: WebGLBuffer;
  private program: WebGLProgram | null = null;
  private uniforms: ShaderUniformLocations = {
    time: null,
    resolution: null,
    primaryColor: null,
    accentColor: null,
    intensity: null,
  };

  constructor(private readonly canvas: HTMLCanvasElement) {
    const gl = canvas.getContext('webgl', {
      alpha: true,
      antialias: false,
      depth: false,
      stencil: false,
    });

    if (!gl) {
      throw new Error('WebGL is not available.');
    }

    const vertexBuffer = gl.createBuffer();
    if (!vertexBuffer) {
      throw new Error('Unable to create fullscreen shader buffer.');
    }

    this.gl = gl;
    this.vertexBuffer = vertexBuffer;
    gl.bindBuffer(gl.ARRAY_BUFFER, vertexBuffer);
    gl.bufferData(
      gl.ARRAY_BUFFER,
      new Float32Array([-1, -1, 3, -1, -1, 3]),
      gl.STATIC_DRAW,
    );
  }

  setFragmentShader(source: string): { ok: true } | { ok: false; message: string } {
    try {
      const nextProgram = compileShaderProgram(this.gl, source);
      if (this.program) {
        this.gl.deleteProgram(this.program);
      }

      this.program = nextProgram;
      this.uniforms = {
        time: this.gl.getUniformLocation(nextProgram, 'u_time'),
        resolution: this.gl.getUniformLocation(nextProgram, 'u_resolution'),
        primaryColor: this.gl.getUniformLocation(nextProgram, 'u_primaryColor'),
        accentColor: this.gl.getUniformLocation(nextProgram, 'u_accentColor'),
        intensity: this.gl.getUniformLocation(nextProgram, 'u_intensity'),
      };

      return { ok: true };
    } catch (error) {
      return {
        ok: false,
        message: error instanceof Error ? error.message : String(error),
      };
    }
  }

  render(params: ShaderRenderParams) {
    if (!this.program) {
      return;
    }

    const { gl } = this;
    const [width, height] = params.resolution;
    gl.viewport(0, 0, width, height);
    gl.useProgram(this.program);
    gl.bindBuffer(gl.ARRAY_BUFFER, this.vertexBuffer);

    const position = gl.getAttribLocation(this.program, 'a_position');
    if (position >= 0) {
      gl.enableVertexAttribArray(position);
      gl.vertexAttribPointer(position, 2, gl.FLOAT, false, 0, 0);
    }

    if (this.uniforms.time) {
      gl.uniform1f(this.uniforms.time, params.timeSeconds);
    }

    if (this.uniforms.resolution) {
      gl.uniform2f(this.uniforms.resolution, width, height);
    }

    if (this.uniforms.primaryColor) {
      gl.uniform4fv(this.uniforms.primaryColor, params.primaryColor);
    }

    if (this.uniforms.accentColor) {
      gl.uniform4fv(this.uniforms.accentColor, params.accentColor);
    }

    if (this.uniforms.intensity) {
      gl.uniform1f(this.uniforms.intensity, params.intensity);
    }

    gl.drawArrays(gl.TRIANGLES, 0, 3);
  }

  dispose() {
    if (this.program) {
      this.gl.deleteProgram(this.program);
      this.program = null;
    }

    this.gl.deleteBuffer(this.vertexBuffer);
  }
}
