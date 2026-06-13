/**
 * 离子长枪扫射特效。
 *
 * 一道高速推进的斜向准直能量束：细亮核心、轻薄青蓝外晕、连续推进端与细碎电离火花。
 * 适合表现光矛、轨道炮、穿刺型舰船系统或高能武器预览。
 */
import type { EffectDefinition } from '../effectDefinition';

/** 离子长枪扫射的 fragment shader（不含公共头）。 */
export const ION_LANCE_FRAGMENT_SHADER = `
uniform float u_beamWidth;
uniform float u_sweepSpeed;
uniform float u_charge;
uniform float u_sparkDensity;
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
  float phase = fract(u_time * u_sweepSpeed);
  float head = mix(-1.35, 1.35, phase);

  // 光矛从左侧生成并向右推进；端部用柔性渐隐，而不是大体积等离子弹头。
  float launchMask = smoothstep(-1.25, -0.92, along);
  float beamEnd = 1.0 - smoothstep(head + 0.02, head + 0.26, along);
  float bodyMask = launchMask * beamEnd;
  float behind = head - along;
  float tailFade = 1.0 - smoothstep(1.35, 1.85, behind);
  float chargePulse = 0.8 + u_charge * (0.12 + 0.06 * sin(u_time * TAU * 3.0));

  // 准直束体：细核心 + 中层束体 + 轻薄外晕。核心不再铺成宽白条，避免等离子炮观感。
  float nearHead = smoothstep(head - 0.40, head + 0.02, along) * beamEnd;
  float coreTaper = 1.0 - smoothstep(head + 0.00, head + 0.24, along);
  float needleWidth = u_beamWidth * 0.58;
  float beamWidth = u_beamWidth * 1.45;
  float haloWidth = u_beamWidth * mix(3.0, 3.8, nearHead);
  float needleCore = exp(-pow(across / max(needleWidth, 1e-4), 2.0)) * bodyMask * tailFade * coreTaper;
  float beamBody = exp(-pow(across / max(beamWidth, 1e-4), 2.0)) * bodyMask * tailFade * (0.72 + nearHead * 0.18);
  float sheath = exp(-pow(across / max(haloWidth, 1e-4), 2.0)) * bodyMask * tailFade * (0.38 + nearHead * 0.18);

  // 低体积的连续推进端：只给束体前端一点收束亮度，不形成独立圆头。
  float ahead = max(along - head, 0.0);
  float trail = max(head - along, 0.0);
  float noseWidth = u_beamWidth * 2.7;
  float noseAxis = exp(-pow(ahead / 0.16 + trail / 0.48, 2.0));
  float headGlow = noseAxis * exp(-pow(across / max(noseWidth, 1e-4), 2.0)) * launchMask * beamEnd * 0.72;

  // 沿光束附近生成稀疏电离火花，不使用 Canvas 描线语义。
  float sparkNoise = fbm(vec2(along * 18.0 + u_time * 5.0, across * 95.0));
  float sparkSharpness = mix(14.0, 5.0, clamp(u_sparkDensity, 0.0, 1.0));
  float sparks = pow(sparkNoise, sparkSharpness)
    * exp(-abs(across) / max(u_beamWidth * 4.8, 1e-4))
    * bodyMask
    * u_sparkDensity;

  float energy = (needleCore * 1.55 * chargePulse + beamBody * 1.15 + sheath * u_glow * 0.62 + headGlow * 1.25 + sparks * 0.9)
    * u_exposure;

  vec3 tint = hsv2rgb(vec3(u_hue, u_saturation * 0.82, 1.0));
  vec3 coreTint = mix(tint, vec3(1.0), 0.72);
  vec3 color = tint * (beamBody * 1.05 + sheath * u_glow * 0.75 + headGlow * 0.7 + sparks * 0.55)
    + coreTint * needleCore * 0.95 * chargePulse;
  color *= u_exposure;
  color = acesTonemap(color);

  float alpha = clamp(max(color.r, max(color.g, color.b)) + energy * 0.06, 0.0, 1.0);
  gl_FragColor = vec4(color, alpha);
}
`;

/** 青白离子长枪扫射特效定义。 */
export const ION_LANCE_SWEEP: EffectDefinition = {
  id: 'ion-lance-sweep',
  name: 'Ion Lance Sweep',
  description: '高速推进的细准直青白离子光束，带轻薄外晕与细碎电离火花。',
  loopSeconds: 3.1,
  fragmentShader: ION_LANCE_FRAGMENT_SHADER,
  parameters: [
    { key: 'beamWidth', label: 'Beam width', min: 0.004, max: 0.05, step: 0.001, defaultValue: 0.011 },
    { key: 'sweepSpeed', label: 'Sweep speed', min: 0.1, max: 1.2, step: 0.02, defaultValue: 0.32 },
    { key: 'charge', label: 'Charge', min: 0, max: 2, step: 0.05, defaultValue: 1.1 },
    { key: 'sparkDensity', label: 'Spark density', min: 0, max: 1, step: 0.02, defaultValue: 0.38 },
    { key: 'angle', label: 'Beam angle', min: -0.6, max: 0.6, step: 0.02, defaultValue: 0.18 },
    { key: 'glow', label: 'Glow', min: 0.3, max: 3, step: 0.05, defaultValue: 1.08 },
    { key: 'hue', label: 'Hue', min: 0, max: 1, step: 0.005, defaultValue: 0.55 },
    { key: 'saturation', label: 'Saturation', min: 0, max: 1, step: 0.01, defaultValue: 0.72 },
    { key: 'exposure', label: 'Exposure', min: 0.5, max: 2.5, step: 0.05, defaultValue: 1.18 },
  ],
};
