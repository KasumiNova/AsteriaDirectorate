/**
 * 六边形护盾绽放特效。
 *
 * 一个通用防护 / 命中反馈效果：圆形护盾边界、内部六向能量格、局部命中光斑与向外扩散的柔和辉光。
 * 可用于护盾命中、屏障展开、修复场或区域增益提示。
 */
import type { EffectDefinition } from '../effectDefinition';

/** 六边形护盾绽放的 fragment shader（不含公共头）。 */
export const AEGIS_HEX_BLOOM_FRAGMENT_SHADER = `
uniform float u_radius;
uniform float u_thickness;
uniform float u_cellScale;
uniform float u_impact;
uniform float u_rotation;
uniform float u_glow;
uniform float u_hue;
uniform float u_saturation;
uniform float u_exposure;

varying vec2 v_uv;

float latticeLine(vec2 p, float angle, float scale) {
  vec2 q = rot(angle) * p;
  float f = abs(fract(q.x * scale) - 0.5);
  return exp(-pow(f / 0.026, 2.0));
}

void main() {
  vec2 p = centeredAspect(v_uv);
  p = rot(u_time * u_rotation * 0.25) * p;
  float r = length(p);

  float shieldMask = 1.0 - smoothstep(u_radius, u_radius + 0.055, r);
  float rim = exp(-pow((r - u_radius) / max(u_thickness, 1e-4), 2.0));
  float innerRim = exp(-pow((r - u_radius * 0.72) / max(u_thickness * 0.72, 1e-4), 2.0)) * 0.28;

  float lineA = latticeLine(p, 0.0, u_cellScale);
  float lineB = latticeLine(p, PI / 3.0, u_cellScale);
  float lineC = latticeLine(p, -PI / 3.0, u_cellScale);
  float grid = max(lineA, max(lineB, lineC)) * shieldMask * smoothstep(u_radius, u_radius * 0.2, r);

  vec2 hitPoint = vec2(-0.25, 0.16);
  float hitDistance = length(p - hitPoint);
  float impactCore = exp(-pow(hitDistance / 0.115, 2.0)) * u_impact;
  float impactWave = exp(-pow((hitDistance - fract(u_time * 0.58) * 0.62) / 0.034, 2.0)) * u_impact * 0.55;
  float breath = 0.84 + 0.16 * sin(u_time * TAU * 0.55);
  float halo = u_radius / (u_radius + r * r * 5.2) * shieldMask * u_glow;

  float energy = (rim * 1.35 + innerRim + grid * 0.38 + impactCore * 1.5 + impactWave + halo * 0.55) * breath * u_exposure;
  vec3 tint = hsv2rgb(vec3(u_hue, u_saturation, 1.0));
  vec3 color = tint * (rim + innerRim + grid * 0.48 + impactWave * 0.75 + halo * 0.52);
  color += mix(tint, vec3(1.0), 0.72) * impactCore;
  color *= u_exposure * breath;
  color = acesTonemap(color);

  float alpha = clamp(max(color.r, max(color.g, color.b)) + energy * 0.045, 0.0, 1.0);
  gl_FragColor = vec4(color, alpha);
}
`;

/** 青色六边形护盾绽放特效定义。 */
export const AEGIS_HEX_BLOOM: EffectDefinition = {
  id: 'aegis-hex-bloom',
  name: 'Aegis Hex Bloom',
  description: '圆形护盾边界内铺开六向能量格，并在局部命中点绽放。',
  loopSeconds: 4,
  fragmentShader: AEGIS_HEX_BLOOM_FRAGMENT_SHADER,
  parameters: [
    { key: 'radius', label: 'Shield radius', min: 0.2, max: 0.9, step: 0.02, defaultValue: 0.58 },
    { key: 'thickness', label: 'Rim thickness', min: 0.006, max: 0.08, step: 0.002, defaultValue: 0.025 },
    { key: 'cellScale', label: 'Cell scale', min: 3, max: 18, step: 0.5, defaultValue: 8.5 },
    { key: 'impact', label: 'Impact bloom', min: 0, max: 2, step: 0.05, defaultValue: 1.05 },
    { key: 'rotation', label: 'Grid rotation', min: -1, max: 1, step: 0.02, defaultValue: 0.28 },
    { key: 'glow', label: 'Glow', min: 0.2, max: 3, step: 0.05, defaultValue: 1.2 },
    { key: 'hue', label: 'Hue', min: 0, max: 1, step: 0.005, defaultValue: 0.52 },
    { key: 'saturation', label: 'Saturation', min: 0, max: 1, step: 0.01, defaultValue: 0.64 },
    { key: 'exposure', label: 'Exposure', min: 0.5, max: 2.5, step: 0.05, defaultValue: 1.18 },
  ],
};