export type ShaderValidationResult =
  | { ok: true }
  | {
      ok: false;
      message: string;
    };

const VERTEX_SHADER_SOURCE = `
attribute vec2 a_position;

void main() {
  gl_Position = vec4(a_position, 0.0, 1.0);
}
`.trim();

const WEBGL2_ONLY_PATTERNS: Array<{ pattern: RegExp; label: string }> = [
  { pattern: /(^|\n)\s*#version\s+300\b/, label: '#version 300' },
  { pattern: /\bout\s+vec4\b/, label: 'out vec4' },
  { pattern: /\btexture\s*\(/, label: 'texture(' },
];

export const createDefaultFragmentShader = (): string =>
  `
precision mediump float;

uniform float u_time;
uniform vec2 u_resolution;
uniform vec4 u_primaryColor;
uniform vec4 u_accentColor;
uniform float u_intensity;

void main() {
  vec2 uv = gl_FragCoord.xy / max(u_resolution.xy, vec2(1.0));
  vec2 center = uv - vec2(0.5);
  float sweep = sin((uv.y + u_time * 0.07) * 72.0) * 0.018;
  float core = smoothstep(0.72, 0.05, length(center * vec2(1.35, 1.0)));
  float flare = smoothstep(0.38, 0.02, distance(uv, vec2(0.18, 0.22)));
  vec3 base = u_primaryColor.rgb * 0.22;
  vec3 glow = u_accentColor.rgb * (core * 0.32 + flare * 0.42 + sweep);
  gl_FragColor = vec4(base + glow * u_intensity, 1.0);
}
`.trim();

export const validateFragmentShaderSource = (source: string): ShaderValidationResult => {
  for (const rule of WEBGL2_ONLY_PATTERNS) {
    if (rule.pattern.test(source)) {
      return {
        ok: false,
        message: `WebGL2-only shader syntax is not supported: ${rule.label}`,
      };
    }
  }

  return { ok: true };
};

const compileShader = (
  gl: WebGLRenderingContext,
  type: typeof gl.VERTEX_SHADER | typeof gl.FRAGMENT_SHADER,
  source: string,
): WebGLShader => {
  const shader = gl.createShader(type);
  if (!shader) {
    throw new Error('Unable to create WebGL shader.');
  }

  gl.shaderSource(shader, source);
  gl.compileShader(shader);

  if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) {
    const message = gl.getShaderInfoLog(shader) || 'Unknown shader compile error.';
    gl.deleteShader(shader);
    throw new Error(message);
  }

  return shader;
};

export const compileShaderProgram = (
  gl: WebGLRenderingContext,
  fragmentSource: string,
): WebGLProgram => {
  const validation = validateFragmentShaderSource(fragmentSource);
  if (!validation.ok) {
    throw new Error(validation.message);
  }

  const vertexShader = compileShader(gl, gl.VERTEX_SHADER, VERTEX_SHADER_SOURCE);
  const fragmentShader = compileShader(gl, gl.FRAGMENT_SHADER, fragmentSource);
  const program = gl.createProgram();

  if (!program) {
    gl.deleteShader(vertexShader);
    gl.deleteShader(fragmentShader);
    throw new Error('Unable to create WebGL shader program.');
  }

  gl.attachShader(program, vertexShader);
  gl.attachShader(program, fragmentShader);
  gl.linkProgram(program);
  gl.deleteShader(vertexShader);
  gl.deleteShader(fragmentShader);

  if (!gl.getProgramParameter(program, gl.LINK_STATUS)) {
    const message = gl.getProgramInfoLog(program) || 'Unknown shader link error.';
    gl.deleteProgram(program);
    throw new Error(message);
  }

  return program;
};
