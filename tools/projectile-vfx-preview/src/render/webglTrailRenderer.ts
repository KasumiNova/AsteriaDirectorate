import { TrailEntityConfig } from '../model/preset';
import { TrailMesh } from './trailGeometry';

export const TRAIL_VERTEX_SHADER_SOURCE = `
attribute vec2 a_position;
attribute vec2 a_uv;
attribute vec4 a_color;
attribute vec4 a_emissive;
attribute float a_alpha;
varying vec2 v_uv;
varying vec4 v_color;
varying vec4 v_emissive;
varying float v_alpha;
void main() {
  vec2 clip = vec2(a_position.x / 360.0, a_position.y / 220.0);
  gl_Position = vec4(clip, 0.0, 1.0);
  v_uv = a_uv;
  v_color = a_color;
  v_emissive = a_emissive;
  v_alpha = a_alpha;
}
`;

export const TRAIL_FRAGMENT_SHADER_SOURCE = `
precision mediump float;
uniform float u_time;
uniform float u_jitterPower;
uniform float u_flickMixValue;
uniform float u_flickerSyncCode;
varying vec2 v_uv;
varying vec4 v_color;
varying vec4 v_emissive;
varying float v_alpha;
float hash(float n) {
  return fract(sin(n) * 43758.5453123);
}
float noise(vec2 p) {
  vec2 i = floor(p);
  vec2 f = fract(p);
  float a = hash(dot(i, vec2(127.1, 311.7)) + u_flickerSyncCode);
  float b = hash(dot(i + vec2(1.0, 0.0), vec2(127.1, 311.7)) + u_flickerSyncCode);
  float c = hash(dot(i + vec2(0.0, 1.0), vec2(127.1, 311.7)) + u_flickerSyncCode);
  float d = hash(dot(i + vec2(1.0, 1.0), vec2(127.1, 311.7)) + u_flickerSyncCode);
  vec2 u = f * f * (3.0 - 2.0 * f);
  return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}
void main() {
  float jitter = (hash(v_uv.y * 31.0 + u_flickerSyncCode) - 0.5) * u_jitterPower * 0.38;
  float edge = min(v_uv.x, 1.0 - v_uv.x);
  float line = smoothstep(0.0, 0.16 + abs(jitter), edge);
  float headBoost = smoothstep(0.78, 1.0, v_uv.y);
  float grain = noise(vec2(v_uv.y * 18.0 - u_time * 3.2, v_uv.x * 5.0));
  float vertical = noise(vec2(v_uv.y * 5.0 - u_time * 0.8, v_uv.x * 22.0));
  float beamNoise = mix(0.82, 1.28, grain) + vertical * 0.16;
  float innerStreak = smoothstep(0.18, 0.5, edge) * smoothstep(0.0, 0.78, v_uv.y);
  vec4 diffuse = v_color * line;
  vec4 emissive = v_emissive * (0.16 + v_emissive.a * 0.38 + headBoost * 0.42 + innerStreak * beamNoise * 0.2);
  diffuse.rgb *= beamNoise;
  gl_FragColor = vec4(diffuse.rgb + emissive.rgb, diffuse.a * v_alpha);
}
`;

export interface WebGLTrailRendererStats {
  drawCalls: number;
  vertexCount: number;
}

export class WebGLTrailRenderer {
  private stats: WebGLTrailRendererStats = { drawCalls: 0, vertexCount: 0 };
  private readonly program: WebGLProgram;
  private readonly buffer: WebGLBuffer;

  constructor(private readonly gl: WebGLRenderingContext) {
    const vertexShader = compileShader(gl, gl.VERTEX_SHADER, TRAIL_VERTEX_SHADER_SOURCE);
    const fragmentShader = compileShader(gl, gl.FRAGMENT_SHADER, TRAIL_FRAGMENT_SHADER_SOURCE);
    this.program = linkProgram(gl, vertexShader, fragmentShader);
    const buffer = gl.createBuffer();
    if (!buffer) {
      throw new Error('Unable to create WebGL buffer');
    }
    this.buffer = buffer;
  }

  clear(width: number, height: number): void {
    this.gl.viewport(0, 0, width, height);
    this.gl.clearColor(0.02, 0.05, 0.1, 1);
    this.gl.clear(this.gl.COLOR_BUFFER_BIT);
  }

  drawTrailMesh(mesh: TrailMesh, config: TrailEntityConfig, timeSeconds: number): void {
    if (mesh.vertices.length === 0) {
      return;
    }

    const gl = this.gl;
    gl.useProgram(this.program);
    gl.enable(gl.BLEND);
    if (config.blendMode === 'additive') {
      gl.blendFunc(gl.SRC_ALPHA, gl.ONE);
    } else {
      gl.blendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA);
    }

    const data = new Float32Array(mesh.vertices.flatMap((vertex) => [
      vertex.position[0], vertex.position[1],
      vertex.uv[0], vertex.uv[1],
      vertex.color[0], vertex.color[1], vertex.color[2], vertex.color[3],
      vertex.emissive[0], vertex.emissive[1], vertex.emissive[2], vertex.emissive[3],
      vertex.alpha,
    ]));
    const stride = 13 * Float32Array.BYTES_PER_ELEMENT;

    gl.bindBuffer(gl.ARRAY_BUFFER, this.buffer);
    gl.bufferData(gl.ARRAY_BUFFER, data, gl.STATIC_DRAW);
    bindAttribute(gl, this.program, 'a_position', 2, stride, 0);
    bindAttribute(gl, this.program, 'a_uv', 2, stride, 2 * 4);
    bindAttribute(gl, this.program, 'a_color', 4, stride, 4 * 4);
    bindAttribute(gl, this.program, 'a_emissive', 4, stride, 8 * 4);
    bindAttribute(gl, this.program, 'a_alpha', 1, stride, 12 * 4);

    setUniform1f(gl, this.program, 'u_time', timeSeconds);
    setUniform1f(gl, this.program, 'u_jitterPower', config.jitterPower);
    setUniform1f(gl, this.program, 'u_flickMixValue', 0);
    setUniform1f(gl, this.program, 'u_flickerSyncCode', config.flickerSyncCode);

    gl.drawArrays(gl.TRIANGLES, 0, mesh.vertices.length);
    this.stats = { drawCalls: this.stats.drawCalls + 1, vertexCount: mesh.vertices.length };
  }

  getStats(): WebGLTrailRendererStats {
    return this.stats;
  }
}

export function createWebGLTrailRenderer(canvas: HTMLCanvasElement, injectedGl?: WebGLRenderingContext): WebGLTrailRenderer | null {
  const gl = injectedGl ?? canvas.getContext('webgl');
  if (!gl) {
    return null;
  }

  return new WebGLTrailRenderer(gl);
}

function compileShader(gl: WebGLRenderingContext, type: number, source: string): WebGLShader {
  const shader = gl.createShader(type);
  if (!shader) {
    throw new Error('Unable to create WebGL shader');
  }
  gl.shaderSource(shader, source);
  gl.compileShader(shader);
  if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) {
    throw new Error(gl.getShaderInfoLog(shader) || 'Shader compilation failed');
  }
  return shader;
}

function linkProgram(gl: WebGLRenderingContext, vertexShader: WebGLShader, fragmentShader: WebGLShader): WebGLProgram {
  const program = gl.createProgram();
  if (!program) {
    throw new Error('Unable to create WebGL program');
  }
  gl.attachShader(program, vertexShader);
  gl.attachShader(program, fragmentShader);
  gl.linkProgram(program);
  if (!gl.getProgramParameter(program, gl.LINK_STATUS)) {
    throw new Error(gl.getProgramInfoLog(program) || 'Program link failed');
  }
  return program;
}

function bindAttribute(gl: WebGLRenderingContext, program: WebGLProgram, name: string, size: number, stride: number, offset: number): void {
  const location = gl.getAttribLocation(program, name);
  if (location >= 0) {
    gl.enableVertexAttribArray(location);
    gl.vertexAttribPointer(location, size, gl.FLOAT, false, stride, offset);
  }
}

function setUniform1f(gl: WebGLRenderingContext, program: WebGLProgram, name: string, value: number): void {
  const location = gl.getUniformLocation(program, name);
  if (location) {
    gl.uniform1f(location, value);
  }
}
