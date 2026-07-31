/**
 * 相位裂隙特效。
 *
 * 一条斜向空间裂缝：黑色核心裂隙、双侧高亮撕裂边缘、细碎相位碎片与轻微折射光晕。
 * 适合表现跃迁裂口、空间撕裂、相位武器命中或护盾破裂边缘。
 */
import type { EffectDefinition } from '../effectDefinition';

/** 相位裂隙的 fragment shader（不含公共头）。 */
export const PHASE_RIFT_FRAGMENT_SHADER = `
uniform float u_slitWidth;
uniform float u_length;
uniform float u_fracture;
uniform float u_drift;
uniform float u_angle;
uniform float u_glow;
uniform float u_hue;
uniform float u_saturation;
uniform float u_exposure;

varying vec2 v_uv;

void main() {
  vec2 p = centeredAspect(v_uv);
  vec2 dir = normalize(vec2(1.0, u_angle));
  vec2 normal = vec2(-dir.y, dir.x);

  float along = dot(p, dir);
  float across = dot(p, normal);
  float axial = abs(along) / max(u_length, 1e-4);
  float endFade = 1.0 - smoothstep(0.74, 1.0, axial);

  // 裂隙边缘沿轴向抖动，形成不规则空间撕裂线。
  float edgeNoise = fbm(vec2(along * 5.4 + u_time * u_drift, across * 16.0));
  float jagged = (edgeNoise - 0.5) * u_fracture * 0.055 * endFade;
  float warpedAcross = across + jagged;

  float coreWidth = u_slitWidth * (0.65 + 0.35 * sin(along * 10.0 + u_time * TAU * u_drift));
  float darkCore = exp(-pow(warpedAcross / max(coreWidth, 1e-4), 2.0)) * endFade;

  // 双侧发光裂边。
  float edgeDistance = abs(abs(warpedAcross) - u_slitWidth * 1.7);
  float hotEdge = exp(-pow(edgeDistance / max(u_slitWidth * 0.75, 1e-4), 2.0)) * endFade;
  float outerGlow = exp(-abs(warpedAcross) / max(u_slitWidth * 8.0, 1e-4)) * endFade;

  // 从裂缝边缘剥离出的相位碎片。
  float shardNoise = fbm(vec2(along * 28.0 - u_time * u_drift * 3.0, warpedAcross * 42.0));
  float shardBand = exp(-pow(edgeDistance / max(u_slitWidth * 3.2, 1e-4), 2.0));
  float shards = pow(shardNoise, 9.0) * shardBand * endFade * u_fracture;

  // 裂缝两端轻微闪烁。
  float tip = exp(-pow((abs(along) - u_length * 0.74) / 0.09, 2.0) - pow(warpedAcross / 0.11, 2.0));

  float energy = (hotEdge * 1.8 + outerGlow * u_glow * 0.62 + shards * 1.4 + tip * 0.8) * u_exposure;

  vec3 tint = hsv2rgb(vec3(u_hue + edgeNoise * 0.05, u_saturation, 1.0));
  vec3 color = tint * energy;
  color = mix(color, vec3(1.0), clamp(hotEdge * 0.55 + shards * 0.45, 0.0, 1.0));
  color = mix(color, vec3(0.0, 0.005, 0.018), clamp(darkCore * 0.78, 0.0, 1.0));
  color = acesTonemap(color);

  float alpha = clamp(max(color.r, max(color.g, color.b)) + energy * 0.05 + darkCore * 0.55, 0.0, 1.0);
  gl_FragColor = vec4(color, alpha);
}
`;

/** 蓝紫相位裂隙特效定义。 */
export const PHASE_RIFT_SLIT: EffectDefinition = {
  id: 'phase-rift-slit',
  name: 'Phase Rift Slit',
  description: '斜向空间裂隙，带黑色核心、撕裂高亮边缘与相位碎片。',
  loopSeconds: 4.6,
  fragmentShader: PHASE_RIFT_FRAGMENT_SHADER,
  parameters: [
    { key: 'slitWidth', label: 'Slit width', min: 0.008, max: 0.08, step: 0.002, defaultValue: 0.026 },
    { key: 'length', label: 'Rift length', min: 0.35, max: 1.35, step: 0.02, defaultValue: 0.92 },
    { key: 'fracture', label: 'Fracture', min: 0, max: 2, step: 0.05, defaultValue: 1.15 },
    { key: 'drift', label: 'Drift speed', min: 0.05, max: 1.5, step: 0.02, defaultValue: 0.42 },
    { key: 'angle', label: 'Rift angle', min: -0.8, max: 0.8, step: 0.02, defaultValue: -0.28 },
    { key: 'glow', label: 'Glow', min: 0.2, max: 3, step: 0.05, defaultValue: 1.25 },
    { key: 'hue', label: 'Hue', min: 0, max: 1, step: 0.005, defaultValue: 0.62 },
    { key: 'saturation', label: 'Saturation', min: 0, max: 1, step: 0.01, defaultValue: 0.78 },
    { key: 'exposure', label: 'Exposure', min: 0.5, max: 2.5, step: 0.05, defaultValue: 1.24 },
  ],
};
