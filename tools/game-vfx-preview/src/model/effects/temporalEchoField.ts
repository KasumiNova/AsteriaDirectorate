/**
 * 时滞回声场特效。
 *
 * 一个通用区域特效：多层半透明残影环、流动尘点与缓慢错位的扫描带，表现时间迟滞或空间干扰。
 * 可用于减速场、隐形残留、传感器干扰或区域异常。
 */
import type { EffectDefinition } from '../effectDefinition';

/** 时滞回声场的 fragment shader（不含公共头）。 */
export const TEMPORAL_ECHO_FIELD_FRAGMENT_SHADER = `
uniform float u_fieldSize;
uniform float u_bandCount;
uniform float u_drift;
uniform float u_noise;
uniform float u_particleDensity;
uniform float u_glow;
uniform float u_hue;
uniform float u_saturation;
uniform float u_exposure;

varying vec2 v_uv;

void main() {
  vec2 p = centeredAspect(v_uv);
  float r = length(p);
  float fieldMask = 1.0 - smoothstep(u_fieldSize, u_fieldSize + 0.16, r);

  float warp = fbm(p * 3.2 + vec2(u_time * u_drift * 0.32, -u_time * u_drift * 0.21));
  float rr = r + (warp - 0.5) * u_noise * 0.08;
  float rings = pow(0.5 + 0.5 * sin(rr * u_bandCount * TAU - u_time * u_drift * TAU), 5.0) * fieldMask;
  float echo = pow(0.5 + 0.5 * sin((p.x * 1.7 + p.y * 0.55) * u_bandCount - u_time * u_drift * 4.0), 7.0) * fieldMask;

  vec2 cells = p * 12.0 + vec2(u_time * u_drift * 0.8, -u_time * u_drift * 0.45);
  vec2 id = floor(cells);
  vec2 local = fract(cells) - 0.5;
  float moteSeed = hash21(id);
  float mote = exp(-dot(local, local) * 68.0) * step(1.0 - u_particleDensity * 0.16, moteSeed) * fieldMask;

  float rim = exp(-pow((rr - u_fieldSize) / 0.045, 2.0));
  float coreMist = exp(-r * r / max(u_fieldSize * u_fieldSize * 0.72, 1e-4)) * 0.28;
  float shimmer = 0.82 + 0.18 * sin(u_time * TAU * 0.7 + warp * TAU);

  float energy = (rings * 0.82 + echo * 0.46 + mote * 1.15 + rim * 0.92 + coreMist * u_glow) * shimmer * u_exposure;
  vec3 tint = hsv2rgb(vec3(u_hue + warp * 0.035, u_saturation, 1.0));
  vec3 color = tint * energy;
  color = mix(color, vec3(1.0), clamp(mote * 0.45 + rim * 0.22, 0.0, 1.0));
  color = acesTonemap(color);

  float alpha = clamp(max(color.r, max(color.g, color.b)) + energy * 0.04, 0.0, 1.0);
  gl_FragColor = vec4(color, alpha);
}
`;

/** 紫蓝时滞回声场特效定义。 */
export const TEMPORAL_ECHO_FIELD: EffectDefinition = {
  id: 'temporal-echo-field',
  name: 'Temporal Echo Field',
  description: '半透明残影环、流动尘点与错位扫描带组成的通用时间干扰场。',
  loopSeconds: 5.4,
  fragmentShader: TEMPORAL_ECHO_FIELD_FRAGMENT_SHADER,
  parameters: [
    { key: 'fieldSize', label: 'Field size', min: 0.25, max: 1.05, step: 0.02, defaultValue: 0.68 },
    { key: 'bandCount', label: 'Band count', min: 2, max: 12, step: 0.5, defaultValue: 6.5 },
    { key: 'drift', label: 'Drift speed', min: 0.05, max: 1.5, step: 0.02, defaultValue: 0.42 },
    { key: 'noise', label: 'Phase noise', min: 0, max: 2, step: 0.05, defaultValue: 0.74 },
    { key: 'particleDensity', label: 'Particle density', min: 0.1, max: 1, step: 0.02, defaultValue: 0.56 },
    { key: 'glow', label: 'Glow', min: 0.2, max: 3, step: 0.05, defaultValue: 0.96 },
    { key: 'hue', label: 'Hue', min: 0, max: 1, step: 0.005, defaultValue: 0.64 },
    { key: 'saturation', label: 'Saturation', min: 0, max: 1, step: 0.01, defaultValue: 0.58 },
    { key: 'exposure', label: 'Exposure', min: 0.5, max: 2.5, step: 0.05, defaultValue: 1.12 },
  ],
};