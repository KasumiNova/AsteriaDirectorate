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
  // 游戏内 ASTDHullModTooltipBackground（astd-hullmod-cluster）的编辑器镜像：
  // u_origin 以 (0,0) 全画布代入，u_variant 用 float uniform（0 呼吸 / 1 流光 / 2 六边形）。
  prismCluster: `
precision mediump float;

uniform float u_time;
uniform vec2 u_resolution;
uniform vec4 u_accentColor;
uniform float u_seed;
uniform float u_variant;

float hash11(float n) {
  return fract(sin(n) * 43758.5453123);
}

vec2 rot2(vec2 p, float a) {
  float c = cos(a);
  float s = sin(a);
  return vec2(c * p.x - s * p.y, s * p.x + c * p.y);
}

float sdTriangle(vec2 p, float r) {
  const float k = 1.7320508;
  p.x = abs(p.x) - r;
  p.y = p.y + r / k;
  if (p.x + k * p.y > 0.0) p = vec2(p.x - k * p.y, -k * p.x - p.y) / 2.0;
  p.x -= clamp(p.x, -2.0 * r, 0.0);
  return -length(p) * sign(p.y);
}

float sdHexagon(vec2 p, float r) {
  const vec3 k = vec3(-0.866025404, 0.5, 0.577350269);
  p = abs(p);
  p -= 2.0 * min(dot(k.xy, p), 0.0) * k.xy;
  p -= vec2(clamp(p.x, -k.z * r, k.z * r), r);
  return length(p) * sign(p.y);
}

void main() {
  vec2 px = gl_FragCoord.xy;
  float w = u_resolution.x;
  float h = u_resolution.y;
  vec3 base = vec3(0.0, 0.01, 0.012);

  float minD = min(
    min(distance(px, vec2(0.0, 0.0)), distance(px, vec2(w, 0.0))),
    min(distance(px, vec2(w, h)), distance(px, vec2(0.0, h))));
  if (minD > 175.0) {
    gl_FragColor = vec4(base, 0.92);
    return;
  }

  float litSide = floor(hash11(u_seed * 91.7) * 4.0);
  float breathSpeed = u_variant > 1.5 ? 0.20 : 0.30;

  float acc = 0.0;
  for (int c = 0; c < 4; c++) {
    vec2 cPos;
    float angDeg;
    if (c == 0) { cPos = vec2(0.0, 0.0); angDeg = 45.0; }
    else if (c == 1) { cPos = vec2(w, 0.0); angDeg = 135.0; }
    else if (c == 2) { cPos = vec2(w, h); angDeg = 225.0; }
    else { cPos = vec2(0.0, h); angDeg = 315.0; }

    float ang = radians(angDeg);
    vec2 dir = vec2(cos(ang), sin(ang));
    vec2 perp = vec2(-dir.y, dir.x);

    bool lit =
      (litSide < 0.5 && (c == 0 || c == 3)) ||
      (litSide >= 0.5 && litSide < 1.5 && (c == 1 || c == 2)) ||
      (litSide >= 1.5 && litSide < 2.5 && (c == 2 || c == 3)) ||
      (litSide >= 2.5 && (c == 0 || c == 1));

    float phase = hash11(u_seed * 131.7 + float(c) * 7.31);
    float sideSign = hash11(u_seed * 557.3 + float(c) * 3.17) < 0.5 ? -1.0 : 1.0;

    for (int k = 0; k < 4; k++) {
      float size;
      vec2 off;
      if (k == 0)      { size = 18.0; off = vec2(32.0, 0.0); }
      else if (k == 1) { size = 12.0; off = vec2(72.0, 0.0); }
      else if (k == 2) { size = 9.0;  off = vec2(66.0, 26.0); }
      else             { size = 7.0;  off = vec2(100.0, -6.0); }
      if (u_variant > 1.5) size *= 0.9;

      float jx = (hash11(u_seed * 371.3 + float(c * 17 + k * 7)) - 0.5) * 5.0;
      float jy = (hash11(u_seed * 733.1 + float(c * 11 + k * 5)) - 0.5) * 5.0;
      vec2 center = cPos + dir * (off.x + jx) + perp * (off.y * sideSign + jy);
      vec2 lp = rot2(px - center, radians(90.0) - ang);

      float sd = u_variant > 1.5 ? sdHexagon(lp, size) : sdTriangle(lp, size);

      bool filled = hash11(u_seed * 917.1 + float(c * 31 + k * 13)) < 0.30;
      float shape;
      if (filled) {
        shape = 1.0 - smoothstep(-0.75, 0.75, sd);
      } else {
        shape = 1.0 - smoothstep(1.0, 1.8, abs(sd));
      }

      float anim;
      if (u_variant > 0.5 && u_variant < 1.5) {
        float proj = (px.x + px.y) / (w + h);
        float band = fract(u_time * 0.16);
        float dd = abs(fract(proj - band + 0.5) - 0.5);
        anim = 0.15 + 0.85 * smoothstep(0.16, 0.02, dd);
      } else {
        float br = 0.5 - 0.5 * cos(6.2831853 * fract(u_time * breathSpeed + phase));
        br = br * br * (3.0 - 2.0 * br);
        anim = br * (lit ? 1.0 : 0.35);
      }

      float alphaK;
      if (k == 0) alphaK = 0.50;
      else if (k == 1) alphaK = 0.42;
      else if (k == 2) alphaK = 0.34;
      else alphaK = 0.26;
      acc = max(acc, shape * alphaK * anim);
    }
  }

  gl_FragColor = vec4(base + u_accentColor.rgb * acc, 0.92);
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

export const TOOLTIP_BACKGROUND_SHADER_PRESETS: Array<{
  id: string;
  name: string;
  fragmentShader: string;
  /** 选中该预设时合入 background.uniforms 的数值 uniform（如动画变体编号）。 */
  uniforms?: Record<string, number>;
}> = [
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
    id: 'prism-cluster',
    name: 'Prism Cluster · 呼吸',
    fragmentShader: shaderPresets.prismCluster,
    uniforms: { u_variant: 0 },
  },
  {
    id: 'prism-cluster-flow',
    name: 'Prism Cluster · 流光',
    fragmentShader: shaderPresets.prismCluster,
    uniforms: { u_variant: 1 },
  },
  {
    id: 'prism-cluster-hex',
    name: 'Prism Cluster · 晶巢',
    fragmentShader: shaderPresets.prismCluster,
    uniforms: { u_variant: 2 },
  },
  {
    id: 'soft-vignette',
    name: 'Soft Vignette',
    fragmentShader: shaderPresets.vignette,
  },
];
