/**
 * 虚空切割束特效。
 *
 * 一道带黑色核心的切割光束：中心为空洞暗线，两侧紫白高温边缘不断抖动并剥离细小电弧。
 * 适合表现切割激光、裂解束、空间撕裂型持续武器。
 */
import type { EffectDefinition } from '../effectDefinition';

/** 虚空切割束的 fragment shader（不含公共头）。 */
export const VOID_CUTTER_BEAM_FRAGMENT_SHADER = `
uniform float u_gapWidth;
uniform float u_edgeWidth;
uniform float u_instability;
uniform float u_scanSpeed;
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

  float cutNoise = fbm(vec2(along * 6.0 - u_time * u_scanSpeed, across * 28.0));
  float warpedAcross = across + (cutNoise - 0.5) * u_instability * u_gapWidth * 1.6;
  float edgeDistance = abs(abs(warpedAcross) - u_gapWidth);

  float voidCore = exp(-pow(warpedAcross / max(u_gapWidth * 0.78, 1e-4), 2.0));
  float hotEdge = exp(-pow(edgeDistance / max(u_edgeWidth, 1e-4), 2.0));
  float outerSheath = exp(-abs(warpedAcross) / max((u_gapWidth + u_edgeWidth) * 6.0, 1e-4)) * u_glow;

  float scan = 0.65 + 0.35 * sin(along * 18.0 - u_time * u_scanSpeed * TAU);
  float arcNoise = fbm(vec2(along * 34.0 + u_time * 4.0, warpedAcross * 52.0));
  float edgeBand = exp(-pow(edgeDistance / max(u_edgeWidth * 3.4, 1e-4), 2.0));
  float arcs = pow(arcNoise, 9.0) * edgeBand * u_instability;

  float energy = (hotEdge * 1.9 * scan + outerSheath * 0.58 + arcs * 1.25) * u_exposure;
  vec3 tint = hsv2rgb(vec3(u_hue + cutNoise * 0.035, u_saturation, 1.0));
  vec3 color = tint * (hotEdge * 1.3 * scan + outerSheath * 0.62 + arcs * 0.9);
  color = mix(color, vec3(1.0), clamp(hotEdge * 0.5 + arcs * 0.42, 0.0, 1.0));
  color = mix(color, vec3(0.0, 0.0, 0.012), clamp(voidCore * 0.86, 0.0, 1.0));
  color *= u_exposure;
  color = acesTonemap(color);

  float alpha = clamp(max(color.r, max(color.g, color.b)) + energy * 0.04 + voidCore * 0.52, 0.0, 1.0);
  gl_FragColor = vec4(color, alpha);
}
`;

/** 紫黑虚空切割束特效定义。 */
export const VOID_CUTTER_BEAM: EffectDefinition = {
  id: 'void-cutter-beam',
  name: 'Void Cutter Beam',
  description: '黑色空洞核心，两侧紫白高温边缘抖动并剥离细小电弧。',
  loopSeconds: 3.6,
  fragmentShader: VOID_CUTTER_BEAM_FRAGMENT_SHADER,
  parameters: [
    { key: 'gapWidth', label: 'Void gap', min: 0.006, max: 0.06, step: 0.001, defaultValue: 0.023 },
    { key: 'edgeWidth', label: 'Edge width', min: 0.004, max: 0.04, step: 0.001, defaultValue: 0.012 },
    { key: 'instability', label: 'Instability', min: 0, max: 2, step: 0.05, defaultValue: 0.86 },
    { key: 'scanSpeed', label: 'Scan speed', min: 0.05, max: 1.8, step: 0.02, defaultValue: 0.58 },
    { key: 'angle', label: 'Beam angle', min: -0.55, max: 0.55, step: 0.02, defaultValue: -0.08 },
    { key: 'glow', label: 'Glow', min: 0.2, max: 3, step: 0.05, defaultValue: 1.18 },
    { key: 'hue', label: 'Hue', min: 0, max: 1, step: 0.005, defaultValue: 0.72 },
    { key: 'saturation', label: 'Saturation', min: 0, max: 1, step: 0.01, defaultValue: 0.76 },
    { key: 'exposure', label: 'Exposure', min: 0.5, max: 2.5, step: 0.05, defaultValue: 1.16 },
  ],
};