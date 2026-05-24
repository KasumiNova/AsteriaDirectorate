import type { TooltipPreset } from './tooltipPreset';

const shaderPresets = {
  scanline: `
precision mediump float;

uniform float u_time;
uniform vec2 u_resolution;

void main() {
  vec2 uv = gl_FragCoord.xy / u_resolution.xy;
  float scan = sin((uv.y + u_time * 0.03) * 280.0) * 0.018;
  float grid = step(0.985, fract(uv.x * 22.0)) * 0.018 + step(0.985, fract(uv.y * 13.0)) * 0.012;
  vec3 base = vec3(0.0, 0.015, 0.018);
  vec3 glow = vec3(0.0, 0.20, 0.24) * smoothstep(0.86, 0.08, distance(uv, vec2(0.18, 0.18)));
  gl_FragColor = vec4(base + glow * 0.28 + scan + grid, 1.0);
}
`.trim(),
  nebula: `
precision mediump float;

uniform float u_time;
uniform vec2 u_resolution;
uniform vec4 u_accentColor;
uniform float u_intensity;

float hash(vec2 p) {
  return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

void main() {
  vec2 uv = gl_FragCoord.xy / u_resolution.xy;
  float n = hash(floor(uv * 42.0 + u_time * 0.5));
  float veil = smoothstep(0.72, 0.05, distance(uv, vec2(0.42, 0.36)));
  vec3 base = vec3(0.0, 0.012, 0.015);
  gl_FragColor = vec4(base + u_accentColor.rgb * (veil * 0.18 + n * 0.018) * u_intensity, 1.0);
}
`.trim(),
  lattice: `
precision mediump float;

uniform float u_time;
uniform vec2 u_resolution;
uniform vec4 u_accentColor;

void main() {
  vec2 uv = gl_FragCoord.xy / u_resolution.xy;
  vec2 cell = abs(fract(uv * vec2(18.0, 11.0)) - 0.5);
  float line = smoothstep(0.018, 0.0, min(cell.x, cell.y));
  float pulse = 0.55 + 0.45 * sin(u_time * 1.2 + uv.x * 8.0);
  gl_FragColor = vec4(vec3(0.0, 0.01, 0.012) + u_accentColor.rgb * line * 0.13 * pulse, 1.0);
}
`.trim(),
  vignette: `
precision mediump float;

uniform float u_time;
uniform vec2 u_resolution;
uniform vec4 u_primaryColor;

void main() {
  vec2 uv = gl_FragCoord.xy / u_resolution.xy;
  float vignette = smoothstep(0.86, 0.22, distance(uv, vec2(0.5)));
  float band = sin((uv.y - u_time * 0.02) * 190.0) * 0.012;
  gl_FragColor = vec4(vec3(0.0, 0.008, 0.01) + u_primaryColor.rgb * vignette * 0.22 + band, 1.0);
}
`.trim(),
};

export const createDefaultHullmodTooltipPreset = (): TooltipPreset => ({
  storageVersion: 'tooltip-style-editor/v3',
  kind: 'hullmod-tooltip',
  hullmod: {
    id: 'hullmod-tooltip',
    displayName: '幅能配送器',
    designType: '普通',
    tierLabel: '设计类型： 普通',
    iconLabel: '',
    opCost: 20,
  },
  theme: {
    panel: {
      width: 580,
      minHeight: 330,
      borderColor: { r: 0, g: 182, b: 221, a: 0.96 },
      backgroundColor: { r: 0, g: 2, b: 3, a: 0.92 },
    },
    text: {
      title: { r: 224, g: 250, b: 255, a: 1 },
      designType: { r: 106, g: 169, b: 255, a: 1 },
      body: { r: 232, g: 244, b: 244, a: 1 },
      muted: { r: 118, g: 139, b: 139, a: 1 },
      warning: { r: 255, g: 224, b: 36, a: 1 },
      positive: { r: 96, g: 224, b: 126, a: 1 },
      orange: { r: 255, g: 148, b: 42, a: 1 },
    },
    section: {
      backgroundColor: { r: 26, g: 70, b: 25, a: 0.88 },
      textColor: { r: 170, g: 255, b: 143, a: 1 },
    },
  },
  background: {
    shaderId: 'scanline-grid',
    fragmentShader: shaderPresets.scanline,
    uniforms: {
      u_time: 0,
      u_resolution: { r: 580, g: 330, b: 0, a: 0 },
      u_primaryColor: { r: 18, g: 72, b: 94, a: 1 },
      u_accentColor: { r: 92, g: 230, b: 255, a: 1 },
      u_intensity: 1,
    },
  },
  blocks: [
    {
      id: 'summary',
      kind: 'paragraph',
      text: '根据船体级别，提高 30 / 60 / 90 / 150 幅能耗散速率，但不如直接提高耗散通道有效，只有前者加满后才有使用价值。',
      highlights: [{ value: '30 / 60 / 90 / 150', colorRole: 'warning' }],
      padTop: 8,
      align: 'start',
    },
    {
      id: 's-mod-heading',
      kind: 'section-heading',
      text: 'S-插件增益',
      padTop: 14,
      align: 'center',
    },
    {
      id: 's-mod-bonus',
      kind: 'paragraph',
      text: '根据船体级别，额外提高 10 / 20 / 30 / 50 幅能耗散，使幅能配送器和增加耗散通道一样有效。',
      highlights: [{ value: '10 / 20 / 30 / 50', colorRole: 'warning' }],
      padTop: 10,
      align: 'start',
    },
    {
      id: 'story-point-note',
      kind: 'paragraph',
      text: '该加成只有在消耗 故事点 将舰船插件内置到船体中之后才能生效。装配消耗低的舰船插件会获得更强的加成。',
      highlights: [{ value: '故事点', colorRole: 'positive' }],
      padTop: 8,
      align: 'start',
    },
  ],
});

export const TOOLTIP_BACKGROUND_SHADER_PRESETS = [
  {
    id: 'scanline-grid',
    name: 'Scanline Grid',
    fragmentShader: shaderPresets.scanline,
  },
  {
    id: 'nebula-veil',
    name: 'Nebula Veil',
    fragmentShader: shaderPresets.nebula,
  },
  {
    id: 'lattice-pulse',
    name: 'Lattice Pulse',
    fragmentShader: shaderPresets.lattice,
  },
  {
    id: 'soft-vignette',
    name: 'Soft Vignette',
    fragmentShader: shaderPresets.vignette,
  },
];
