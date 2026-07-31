/**
 * 等离子能量核心特效。
 *
 * 一团脉动的等离子球：球体表面由流动的分形噪声扰动，呈现翻腾的能量质感，
 * 外围带柔和光晕。适合表现引擎核心、能量充能、护盾发生器等持续能量源。
 */
import type { EffectDefinition } from '../effectDefinition';

/** 等离子核心的 fragment shader（不含公共头）。 */
export const PLASMA_CORE_FRAGMENT_SHADER = `
uniform float u_coreSize;
uniform float u_turbulence;
uniform float u_pulse;
uniform float u_speed;
uniform float u_glow;
uniform float u_hue;
uniform float u_saturation;
uniform float u_exposure;

varying vec2 v_uv;

void main() {
  vec2 p = centeredAspect(v_uv);
  float r = length(p);

  float pulse = 1.0 + u_pulse * 0.25 * sin(u_time * TAU * 0.8);
  float radius = u_coreSize * pulse;

  // 域扭曲的流动噪声：内层 fbm 驱动外层 fbm，得到翻腾的等离子纹理。
  vec2 flow = vec2(u_time * u_speed * 0.25, u_time * u_speed * -0.18);
  float n = fbm(p * 4.0 + flow + fbm(p * 2.5 - flow));

  // 球体柔边遮罩。
  float sphere = smoothstep(radius * 1.5, radius * 0.1, r);
  // 高对比丝状湍流，仅在球体内部可见，形成翻腾的等离子细节。
  float filaments = pow(n, 2.2) * u_turbulence;
  float body = sphere * (0.3 + filaments * 1.4);

  // 明亮内核与长尾光晕。
  float core = smoothstep(radius * 0.65, 0.0, r);
  float halo = u_coreSize / (u_coreSize + r * r * 5.5) * 0.7;

  float energy = (body * 1.3 + core * 1.1 + halo * u_glow) * u_exposure;

  // 色相随湍流漂移，增强等离子的不稳定感。
  vec3 tint = hsv2rgb(vec3(u_hue + (n - 0.5) * 0.08, u_saturation, 1.0));
  vec3 color = tint * energy;
  color = mix(color, vec3(1.0), clamp(core * 0.7, 0.0, 1.0));
  color = acesTonemap(color);

  float alpha = clamp(max(color.r, max(color.g, color.b)) + energy * 0.06, 0.0, 1.0);
  gl_FragColor = vec4(color, alpha);
}
`;

/** 紫色等离子能量核心特效定义。 */
export const PLASMA_CORE: EffectDefinition = {
  id: 'plasma-core',
  name: 'Plasma Core',
  description: '翻腾脉动的等离子能量球，适合引擎核心或能量充能。',
  loopSeconds: 5,
  fragmentShader: PLASMA_CORE_FRAGMENT_SHADER,
  parameters: [
    { key: 'coreSize', label: 'Core size', min: 0.08, max: 0.5, step: 0.01, defaultValue: 0.26 },
    { key: 'turbulence', label: 'Turbulence', min: 0, max: 2, step: 0.05, defaultValue: 0.9 },
    { key: 'pulse', label: 'Pulse', min: 0, max: 1, step: 0.01, defaultValue: 0.4 },
    { key: 'speed', label: 'Flow speed', min: 0.1, max: 2, step: 0.05, defaultValue: 0.7 },
    { key: 'glow', label: 'Glow', min: 0.3, max: 3, step: 0.05, defaultValue: 1.3 },
    { key: 'hue', label: 'Hue', min: 0, max: 1, step: 0.005, defaultValue: 0.74 },
    { key: 'saturation', label: 'Saturation', min: 0, max: 1, step: 0.01, defaultValue: 0.7 },
    { key: 'exposure', label: 'Exposure', min: 0.5, max: 2.5, step: 0.05, defaultValue: 1.2 },
  ],
};
