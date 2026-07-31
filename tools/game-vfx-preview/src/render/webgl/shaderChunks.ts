/**
 * WebGL 着色器公共片段。
 *
 * 提供全屏 pass 使用的顶点着色器，以及被各特效 fragment shader 复用的
 * GLSL 工具函数（坐标变换、旋转、噪声、色调映射、HSV）。通用渲染器会把
 * {@link GLSL_COMMON} 拼接到每个特效 fragment shader 之前，因此特效着色器
 * 自身只需声明私有 uniform 与 main，无需重复声明精度或内建 uniform。
 */

/** 全屏 pass 顶点着色器：把裁剪空间四边形顶点转换为 [0,1] 的 UV。 */
export const FULLSCREEN_VERTEX_SHADER = `
attribute vec2 a_position;
varying vec2 v_uv;

void main() {
  v_uv = a_position * 0.5 + 0.5;
  gl_Position = vec4(a_position, 0.0, 1.0);
}
`;

/**
 * 公共 GLSL 头：精度声明、常量、内建 uniform（u_time / u_resolution）与工具函数。
 *
 * 不包含任何特效私有 uniform，可安全前置到任意特效 fragment shader。所有特效
 * 共享 {@link GLSL_COMMON} 中的辅助函数，避免在每个着色器里重复实现噪声与色彩工具。
 */
export const GLSL_COMMON = `
precision highp float;

uniform float u_time;
uniform vec2 u_resolution;

const float PI = 3.14159265359;
const float TAU = 6.28318530718;

// 2D 旋转矩阵。
mat2 rot(float a) {
  float c = cos(a);
  float s = sin(a);
  return mat2(c, -s, s, c);
}

// 屏幕 UV -> 居中且纵横比校正的坐标（中心为原点，纵向范围约 [-1, 1]）。
vec2 centeredAspect(vec2 uv) {
  vec2 p = uv * 2.0 - 1.0;
  p.x *= u_resolution.x / max(u_resolution.y, 1.0);
  return p;
}

// 标量哈希噪声，返回 [0, 1)。
float hash21(vec2 p) {
  p = fract(p * vec2(123.34, 345.45));
  p += dot(p, p + 34.345);
  return fract(p.x * p.y);
}

// 平滑插值的值噪声。
float valueNoise(vec2 p) {
  vec2 i = floor(p);
  vec2 f = fract(p);
  vec2 u = f * f * (3.0 - 2.0 * f);
  float a = hash21(i);
  float b = hash21(i + vec2(1.0, 0.0));
  float c = hash21(i + vec2(0.0, 1.0));
  float d = hash21(i + vec2(1.0, 1.0));
  return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

// 5 个倍频的分形布朗运动。
float fbm(vec2 p) {
  float value = 0.0;
  float amplitude = 0.5;
  for (int i = 0; i < 5; i++) {
    value += amplitude * valueNoise(p);
    p *= 2.02;
    amplitude *= 0.5;
  }
  return value;
}

// HSV -> RGB。
vec3 hsv2rgb(vec3 c) {
  vec3 p = abs(fract(c.xxx + vec3(0.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0);
  return c.z * mix(vec3(1.0), clamp(p - 1.0, 0.0, 1.0), c.y);
}

// ACES filmic 近似色调映射，把 HDR 能量压回可显示范围。
vec3 acesTonemap(vec3 x) {
  const float a = 2.51;
  const float b = 0.03;
  const float c = 2.43;
  const float d = 0.59;
  const float e = 0.14;
  return clamp((x * (a * x + b)) / (x * (c * x + d) + e), 0.0, 1.0);
}
`;
