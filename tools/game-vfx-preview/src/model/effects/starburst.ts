/**
 * 星芒 / 镜头光晕特效。
 *
 * 复刻参考图：一颗白热核心向外发出略带旋转、长度不对称的四角星光线，
 * 叠加柔和辉光与镜头重影（光斑 + 光环）。颜色完全由 `hue / saturation`
 * 参数控制，因此蓝色星芒与绯红冲击闪光共享同一份 fragment shader，
 * 仅默认参数不同。
 */
import type { EffectDefinition, ParameterSpec } from '../effectDefinition';

/** 星芒特效的 fragment shader（不含公共头，由渲染器拼接 GLSL_COMMON）。 */
export const STARBURST_FRAGMENT_SHADER = `
uniform float u_coreSize;
uniform float u_glow;
uniform float u_rayLength;
uniform float u_rayWidth;
uniform float u_spin;
uniform float u_pulse;
uniform float u_flare;
uniform float u_hue;
uniform float u_saturation;
uniform float u_exposure;

varying vec2 v_uv;

// 单条锥形针光线：从原点沿 angle 方向发出，越远越细、越暗。
float spike(vec2 p, float angle, float len, float width) {
  vec2 q = rot(-angle) * p;
  float along = q.x;
  if (along < 0.0) {
    return 0.0;
  }
  float t = along / max(len, 1e-4);
  float taper = max(0.0, 1.0 - t);
  float w = max(width * taper, 1e-5);
  float lateral = exp(-(q.y * q.y) / (w * w));
  float fade = exp(-t * 2.2) * taper;
  return lateral * fade;
}

// 软边圆盘，用作镜头重影光斑。
float disc(vec2 p, vec2 center, float radius, float soft) {
  float d = length(p - center);
  return 1.0 - smoothstep(radius * (1.0 - soft), radius, d);
}

// 高斯圆环，用作镜头光环。
float ringShape(vec2 p, vec2 center, float radius, float width) {
  float d = abs(length(p - center) - radius);
  return exp(-(d * d) / max(width * width, 1e-6));
}

// 沿固定轴排布的镜头重影集合，带轻微闪烁。
float lensFlare(vec2 p, float t) {
  vec2 axis = normalize(vec2(0.92, 0.46));
  float f = 0.0;
  f += disc(p, axis * 0.86, 0.045, 0.7) * 0.55;
  f += disc(p, axis * 0.45, 0.022, 0.8) * 0.40;
  f += disc(p, -axis * 0.55, 0.030, 0.75) * 0.32;
  f += disc(p, -axis * 0.28, 0.016, 0.85) * 0.28;
  f += ringShape(p, axis * 1.05, 0.20, 0.02) * 0.35;
  f += ringShape(p, vec2(0.0), 0.46, 0.012) * 0.22;
  return f * (0.82 + 0.18 * sin(t * 5.0));
}

void main() {
  vec2 p = centeredAspect(v_uv);
  float r = length(p);
  float spin = u_time * u_spin * 0.6;
  float pulse = 1.0 + u_pulse * 0.45 * sin(u_time * TAU);

  // 核心：高斯热核 + 长尾辉光。
  float coreHot = exp(-(r * r) / max(u_coreSize * u_coreSize, 1e-5));
  float bloom = u_coreSize / (u_coreSize + r * r * 3.2);

  // 不对称四角星，叠加较短的水平/垂直十字。
  float base = spin + 1.18;
  float rays = 0.0;
  rays += spike(p, base, u_rayLength * 1.00, u_rayWidth) * 1.00;
  rays += spike(p, base + PI, u_rayLength * 1.32, u_rayWidth) * 0.92;
  rays += spike(p, base - 1.92, u_rayLength * 0.72, u_rayWidth * 0.85) * 0.72;
  rays += spike(p, base - 1.92 + PI, u_rayLength * 0.86, u_rayWidth * 0.85) * 0.60;
  rays += spike(p, base + PI * 0.5, u_rayLength * 0.42, u_rayWidth * 0.6) * 0.40;
  rays += spike(p, base - PI * 0.5, u_rayLength * 0.42, u_rayWidth * 0.6) * 0.40;

  float flare = lensFlare(p, u_time) * u_flare;

  float energy = (coreHot * 1.8 + bloom * u_glow + rays * pulse * 2.2 + flare) * u_exposure;

  vec3 tint = hsv2rgb(vec3(u_hue, u_saturation, 1.0));
  vec3 color = tint * energy;
  // 核心区域趋白。
  color = mix(color, vec3(1.0), clamp(coreHot * 1.1, 0.0, 1.0));
  color = acesTonemap(color);

  float alpha = clamp(max(color.r, max(color.g, color.b)) * 1.1 + energy * 0.05, 0.0, 1.0);
  gl_FragColor = vec4(color, alpha);
}
`;

/** 星芒特效默认参数集合，用于在蓝/红变体间复用 shader。 */
export interface StarburstDefaults {
  coreSize: number;
  glow: number;
  rayLength: number;
  rayWidth: number;
  spin: number;
  pulse: number;
  flare: number;
  hue: number;
  saturation: number;
  exposure: number;
}

/** 根据给定默认值生成星芒参数规格列表。 */
export function starburstParameters(defaults: StarburstDefaults): ParameterSpec[] {
  return [
    { key: 'coreSize', label: 'Core size', min: 0.02, max: 0.25, step: 0.005, defaultValue: defaults.coreSize },
    { key: 'glow', label: 'Glow', min: 0.2, max: 3, step: 0.05, defaultValue: defaults.glow },
    { key: 'rayLength', label: 'Ray length', min: 0.3, max: 1.6, step: 0.02, defaultValue: defaults.rayLength },
    { key: 'rayWidth', label: 'Ray width', min: 0.004, max: 0.05, step: 0.001, defaultValue: defaults.rayWidth },
    { key: 'spin', label: 'Spin', min: 0, max: 1, step: 0.01, defaultValue: defaults.spin },
    { key: 'pulse', label: 'Pulse', min: 0, max: 1, step: 0.01, defaultValue: defaults.pulse },
    { key: 'flare', label: 'Lens flare', min: 0, max: 1.5, step: 0.05, defaultValue: defaults.flare },
    { key: 'hue', label: 'Hue', min: 0, max: 1, step: 0.005, defaultValue: defaults.hue },
    { key: 'saturation', label: 'Saturation', min: 0, max: 1, step: 0.01, defaultValue: defaults.saturation },
    { key: 'exposure', label: 'Exposure', min: 0.5, max: 2.5, step: 0.05, defaultValue: defaults.exposure },
  ];
}

/** 蓝色旋转星芒，对应参考图的青蓝色镜头光晕。 */
export const BLUE_STARBURST: EffectDefinition = {
  id: 'rotating-blue-starburst',
  name: 'Rotating Blue Starburst',
  description: '青蓝色旋转星芒，白热核心配不对称四角星与镜头重影。',
  loopSeconds: 4,
  fragmentShader: STARBURST_FRAGMENT_SHADER,
  parameters: starburstParameters({
    coreSize: 0.09,
    glow: 1.4,
    rayLength: 0.95,
    rayWidth: 0.012,
    spin: 0.15,
    pulse: 0.35,
    flare: 0.7,
    hue: 0.55,
    saturation: 0.7,
    exposure: 1.2,
  }),
};

/** 绯红冲击闪光，对应参考图的红色星芒命中特效。 */
export const CRIMSON_FLARE: EffectDefinition = {
  id: 'crimson-impact-flare',
  name: 'Crimson Impact Flare',
  description: '绯红命中闪光，强脉动核心配尖锐光刺，适合武器命中瞬间。',
  loopSeconds: 1.6,
  fragmentShader: STARBURST_FRAGMENT_SHADER,
  parameters: starburstParameters({
    coreSize: 0.07,
    glow: 1.6,
    rayLength: 1.1,
    rayWidth: 0.014,
    spin: 0.05,
    pulse: 0.65,
    flare: 0.5,
    hue: 0.0,
    saturation: 0.88,
    exposure: 1.4,
  }),
};
