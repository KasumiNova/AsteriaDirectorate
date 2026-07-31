/**
 * 冲击波环特效。
 *
 * 命中 / 爆炸常见的能量冲击波：从中心向外扩散的多重高亮圆环，边缘带湍流扰动，
 * 中心伴随一次闪光。环随时间循环扩散并逐渐淡出，便于在游戏中表现范围冲击。
 */
import type { EffectDefinition } from '../effectDefinition';

/** 冲击波环的 fragment shader（不含公共头）。 */
export const SHOCKWAVE_FRAGMENT_SHADER = `
uniform float u_speed;
uniform float u_thickness;
uniform float u_ringCount;
uniform float u_distortion;
uniform float u_glow;
uniform float u_hue;
uniform float u_saturation;
uniform float u_exposure;

varying vec2 v_uv;

void main() {
  vec2 p = centeredAspect(v_uv);
  float r = length(p);

  // 用单位方向向量喂噪声，避免 atan 在 ±PI 处产生可见接缝。
  vec2 dir = p / max(r, 1e-4);
  float wobble = (fbm(dir * 3.0 + vec2(u_time * 0.5, 0.0)) - 0.5) * u_distortion;
  float rr = r + wobble * 0.08;

  // 最多 4 重环，由 u_ringCount 控制实际数量；各环相位错开扩散。
  float energy = 0.0;
  for (int i = 0; i < 4; i++) {
    if (float(i) >= u_ringCount) {
      break;
    }
    float phase = fract(u_time * u_speed - float(i) * 0.26);
    float radius = phase * 1.3;
    float ring = exp(-pow((rr - radius) / max(u_thickness, 1e-3), 2.0));
    float fade = 1.0 - phase;
    energy += ring * fade;
  }

  // 中心闪光。
  float flash = exp(-rr * rr * 8.0) * 0.6;
  energy = (energy * u_glow + flash) * u_exposure;

  vec3 tint = hsv2rgb(vec3(u_hue, u_saturation, 1.0));
  vec3 color = tint * energy;
  color = mix(color, vec3(1.0), clamp(flash, 0.0, 1.0));
  color = acesTonemap(color);

  float alpha = clamp(max(color.r, max(color.g, color.b)) + energy * 0.1, 0.0, 1.0);
  gl_FragColor = vec4(color, alpha);
}
`;

/** 青色冲击波环特效定义。 */
export const SHOCKWAVE_RING: EffectDefinition = {
  id: 'shockwave-ring',
  name: 'Shockwave Ring',
  description: '向外扩散的多重能量冲击波环，中心带闪光，适合范围爆炸。',
  loopSeconds: 2.4,
  fragmentShader: SHOCKWAVE_FRAGMENT_SHADER,
  parameters: [
    { key: 'speed', label: 'Expand speed', min: 0.1, max: 1.5, step: 0.02, defaultValue: 0.5 },
    { key: 'thickness', label: 'Ring thickness', min: 0.01, max: 0.2, step: 0.005, defaultValue: 0.05 },
    { key: 'ringCount', label: 'Ring count', min: 1, max: 4, step: 1, defaultValue: 3 },
    { key: 'distortion', label: 'Edge distortion', min: 0, max: 2, step: 0.05, defaultValue: 0.6 },
    { key: 'glow', label: 'Glow', min: 0.3, max: 3, step: 0.05, defaultValue: 1.5 },
    { key: 'hue', label: 'Hue', min: 0, max: 1, step: 0.005, defaultValue: 0.52 },
    { key: 'saturation', label: 'Saturation', min: 0, max: 1, step: 0.01, defaultValue: 0.6 },
    { key: 'exposure', label: 'Exposure', min: 0.5, max: 2.5, step: 0.05, defaultValue: 1.25 },
  ],
};
