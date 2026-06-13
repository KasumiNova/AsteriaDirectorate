/**
 * WebGL 着色器与 program 编译工具。
 *
 * 与具体特效解耦，仅负责把 GLSL 源码编译、链接为可用的 {@link WebGLProgram}，
 * 并在失败时抛出带有编译/链接日志的错误，便于定位 shader 问题。
 */

/**
 * 编译单个着色器。
 *
 * @param gl WebGL 上下文。
 * @param type 着色器类型（gl.VERTEX_SHADER 或 gl.FRAGMENT_SHADER）。
 * @param source GLSL 源码。
 * @returns 编译成功的着色器对象。
 * @throws 创建或编译失败时抛出，错误信息包含驱动返回的日志。
 */
export function compileShader(gl: WebGLRenderingContext, type: number, source: string): WebGLShader {
  const shader = gl.createShader(type);
  if (!shader) {
    throw new Error('Failed to create WebGL shader');
  }
  gl.shaderSource(shader, source);
  gl.compileShader(shader);
  if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) {
    const log = gl.getShaderInfoLog(shader) || 'unknown shader compile error';
    gl.deleteShader(shader);
    throw new Error(`Failed to compile shader: ${log}`);
  }
  return shader;
}

/**
 * 把顶点与片段着色器源码链接为一个 program。
 *
 * 中间着色器对象在链接后立即删除（program 已持有引用），避免泄漏。
 *
 * @param gl WebGL 上下文。
 * @param vertexSource 顶点着色器源码。
 * @param fragmentSource 片段着色器源码。
 * @returns 链接成功的 program。
 * @throws 创建或链接失败时抛出，错误信息包含链接日志。
 */
export function linkProgram(
  gl: WebGLRenderingContext,
  vertexSource: string,
  fragmentSource: string,
): WebGLProgram {
  const vertexShader = compileShader(gl, gl.VERTEX_SHADER, vertexSource);
  const fragmentShader = compileShader(gl, gl.FRAGMENT_SHADER, fragmentSource);
  const program = gl.createProgram();
  if (!program) {
    gl.deleteShader(vertexShader);
    gl.deleteShader(fragmentShader);
    throw new Error('Failed to create WebGL program');
  }
  gl.attachShader(program, vertexShader);
  gl.attachShader(program, fragmentShader);
  gl.linkProgram(program);
  gl.deleteShader(vertexShader);
  gl.deleteShader(fragmentShader);

  if (!gl.getProgramParameter(program, gl.LINK_STATUS)) {
    const log = gl.getProgramInfoLog(program) || 'unknown program link error';
    gl.deleteProgram(program);
    throw new Error(`Failed to link program: ${log}`);
  }
  return program;
}
