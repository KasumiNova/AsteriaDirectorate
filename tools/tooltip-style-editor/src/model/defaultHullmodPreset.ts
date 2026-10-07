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
  // 游戏内 ASTDHullModTooltipBackground 主风格（corner-pulse，k3-01 角标脉冲）的编辑器镜像：
  // u_origin 以 (0,0) 全画布代入，输出 alpha 固定 0.92。
  cornerPulse: `
precision highp float;

uniform float u_time;
uniform vec2 u_resolution;
uniform vec4 u_accentColor;

const vec3 BG = vec3(0.015, 0.025, 0.0425);
const float PI = 3.14159265359;

float hash12(vec2 p) {
  vec3 p3 = fract(vec3(p.xyx) * 0.1031);
  p3 += dot(p3, p3.yzx + 33.33);
  return fract((p3.x + p3.y) * p3.z);
}

float sdTri(vec2 p, float r) {
  const float k = 1.7320508;
  p.x = abs(p.x) - r;
  p.y = p.y + r / k;
  if (p.x + k * p.y > 0.0) p = vec2(p.x - k * p.y, -k * p.x - p.y) / 2.0;
  p.x -= clamp(p.x, -2.0 * r, 0.0);
  return -length(p) * sign(p.y);
}

float sdSeg(vec2 p, vec2 a, vec2 b) {
  vec2 pa = p - a, ba = b - a;
  float h = clamp(dot(pa, ba) / dot(ba, ba), 0.0, 1.0);
  return length(pa - ba * h);
}

float triGrid(vec2 p, float s) {
  float d = 1e5;
  for (int i = 0; i < 3; i++) {
    float a = float(i) * PI / 3.0;
    vec2 n = vec2(cos(a), sin(a));
    float v = abs(fract(dot(p, n) / s + 0.5) - 0.5) * s;
    d = min(d, v);
  }
  return d;
}

void main() {
  float uiScale = clamp(u_resolution.x / 580.0, 0.6, 1.0);
  vec2 frag = gl_FragCoord.xy / uiScale;
  vec2 res = u_resolution / uiScale;
  vec2 uv = frag / res;
  vec3 accent = u_accentColor.rgb;
  vec3 col = BG;

  col += vec3(0.005, 0.010, 0.01875) * (1.0 - uv.y);

  float g = triGrid(frag, 56.0);
  float gridLine = 1.0 - smoothstep(0.0, 1.2, g);
  col += accent * gridLine * 0.0625;

  float sweepY = mod(u_time * 81.0, res.y + 240.0) - 120.0;
  float band = exp(-pow((frag.y - sweepY) / 46.0, 2.0));
  col += accent * gridLine * band * 0.11;
  col += accent * band * 0.012;

  {
    float cell = 90.0;
    vec2 gp = frag + vec2(0.0, u_time * 7.0);
    vec2 id = floor(gp / cell);
    vec2 lv = fract(gp / cell) * cell;
    float rnd = hash12(id);
    vec2 ctr = vec2(hash12(id + 7.1), hash12(id + 3.7)) * cell;
    float sz = 3.0 + 5.0 * hash12(id + 11.3);
    float tw = 0.5 + 0.5 * sin(u_time * (0.8 + rnd * 1.6) + rnd * 6.2832);
    float d = sdTri(lv - ctr, sz);
    float a = (1.0 - smoothstep(0.0, 1.0, abs(d))) * 0.175 * tw * step(0.55, rnd);
    col += accent * a;
  }

  // corner ornaments: L-shaped brackets
  // (nested triangle strokes / apex light / energy core temporarily removed per spec)
  for (int i = 0; i < 4; i++) {
    vec2 corner;
    if (i == 0) { corner = vec2(10.0, 10.0); }
    else if (i == 1) { corner = vec2(res.x - 10.0, 10.0); }
    else if (i == 2) { corner = vec2(res.x - 10.0, res.y - 10.0); }
    else { corner = vec2(10.0, res.y - 10.0); }

    // early-out: bracket arm reach 50.4 units + glow ~20, normalized 580px-baseline space
    if (distance(frag, corner) > 95.0) continue;

    float phase = float(i) * 1.7;
    float breathe  = 0.62 + 0.38 * sin(u_time * 1.5 + phase);

    vec2 ex = (corner.x < res.x * 0.5) ? vec2(1.0, 0.0) : vec2(-1.0, 0.0);
    vec2 ey = (corner.y < res.y * 0.5) ? vec2(0.0, 1.0) : vec2(0.0, -1.0);
    float bl = min(sdSeg(frag, corner, corner + ex * 50.4),
                   sdSeg(frag, corner, corner + ey * 50.4));
    col += accent * (1.0 - smoothstep(0.5, 1.5, bl)) * 0.75 * breathe;
    col += accent * exp(-bl * 0.25) * 0.08 * breathe;
    vec2 corner2 = corner - (ex + ey) * 4.2;
    float bl2 = min(sdSeg(frag, corner2, corner2 + ex * 29.4),
                    sdSeg(frag, corner2, corner2 + ey * 29.4));
    col += accent * (1.0 - smoothstep(0.5, 1.5, bl2)) * 0.38;
  }

  float bd = min(min(frag.x, res.x - frag.x), min(frag.y, res.y - frag.y));
  col += accent * (1.0 - smoothstep(0.0, 1.0, bd)) * 0.10;

  col *= 0.96 + 0.04 * sin(frag.y * PI);

  vec2 q = uv - 0.5;
  col *= 1.0 - 0.32 * dot(q, q);

  col += (hash12(frag + fract(u_time) * 13.7) - 0.5) * 0.008;

  col = col / (1.0 + col * 0.12);

  gl_FragColor = vec4(col, 0.92);
}
`.trim(),
  // 游戏内 ASTDHullModTooltipBackground 备选风格（prism-lattice，deepseek-02 棱镜栅格）镜像：
  // u_origin 以 (0,0) 代入，输出 alpha 固定 0.92；扩散三角环与旋转主轴已移除。
  prismLattice: `
precision highp float;

uniform float u_time;
uniform vec2 u_resolution;
uniform vec4 u_accentColor;

// panel half-extents in p units: border flush with the panel edge
const vec2 PANEL = vec2(1.0, 1.0);

float hash21(vec2 p) {
  p = fract(p * vec2(123.34, 456.21));
  p += dot(p, p + 45.32);
  return fract(p.x * p.y);
}
float hash11(float x) { return fract(sin(x * 127.1) * 43758.5453); }

float triSDF(vec2 p, float r) {
  const float k = 1.7320508;
  p.x = abs(p.x) - r;
  p.y = p.y + r / k;
  if (p.x + k * p.y > 0.0) p = vec2(p.x - k * p.y, -k * p.x - p.y) / 2.0;
  p.x -= clamp(p.x, -2.0 * r, 0.0);
  return -length(p) * sign(p.y);
}

float segDist(vec2 p, vec2 a, vec2 b, out float t) {
  vec2 pa = p - a, ba = b - a;
  t = clamp(dot(pa, ba) / max(dot(ba, ba), 1e-6), 0.0, 1.0);
  return length(pa - ba * t);
}

void rectOutline(vec2 p, vec2 h, out float d, out float arc) {
  vec2 a0 = vec2(-h.x, -h.y), a1 = vec2(h.x, -h.y);
  vec2 b0 = vec2( h.x, -h.y), b1 = vec2(h.x,  h.y);
  vec2 c0 = vec2( h.x,  h.y), c1 = vec2(-h.x, h.y);
  vec2 e0 = vec2(-h.x,  h.y), e1 = vec2(-h.x, -h.y);
  float t0, t1, t2, t3;
  float d0 = segDist(p, a0, a1, t0);
  float d1 = segDist(p, b0, b1, t1);
  float d2 = segDist(p, c0, c1, t2);
  float d3 = segDist(p, e0, e1, t3);
  float per = 2.0 * h.x, we = 2.0 * h.y;
  d = d0; arc = t0 * per;
  if (d1 < d) { d = d1; arc = per + t1 * we; }
  if (d2 < d) { d = d2; arc = per + we + t2 * per; }
  if (d3 < d) { d = d3; arc = 2.0 * per + we + t3 * we; }
}

float lattice(vec2 p, float t) {
  float s = 0.0;
  for (int i = 0; i < 3; i++) {
    float a = float(i) * 2.0943951 + 0.15 * sin(t * 0.21);
    vec2  d = vec2(cos(a), sin(a));
    float ph = dot(p, d) * 26.0 + t * (0.35 + 0.12 * float(i));
    s += exp(-abs(sin(ph)) * 14.0);
  }
  return s;
}

void main() {
  vec2 px = gl_FragCoord.xy;
  float aspect = u_resolution.x / u_resolution.y;
  vec2 p = (2.0 * px - u_resolution) / u_resolution.y;
  float R = length(p);
  float t = u_time;
  vec3 acc = u_accentColor.rgb;

  vec2 hp = vec2(PANEL.x * aspect, PANEL.y);
  vec2 a  = abs(p);
  vec2 cu = vec2(hp.x - a.x, hp.y - a.y);
  float cornerId = step(0.0, p.x) + step(0.0, p.y) * 2.0;

  vec3 col = vec3(0.0);

  float cw = exp(-mix(length(cu), max(cu.x, cu.y), 0.68) * 6.0);
  if (cw > 0.004) {
    vec2 dir = normalize(p + 1e-5);
    float off = 0.0030;
    vec3 lat = vec3(
      lattice(p + dir * off * 2.0, t),
      lattice(p, t),
      lattice(p - dir * off * 2.0, t)
    );
    float latMono = (lat.r + lat.g + lat.b) / 3.0;
    float latPow = smoothstep(0.35, 0.95, latMono);
    float latBase = 0.085 * cw;
    col += acc * latPow * latBase * (0.80 + 0.40 * lat);

    vec2 gid = floor(p * 22.0);
    float cell = hash21(gid);
    vec2 gc = (gid + 0.5) / 22.0;
    float node = 1.0 - smoothstep(0.0, 0.008, length(p - gc));
    col += acc * node * step(0.97, cell) * cw *
           (0.35 + 0.65 * max(0.0, sin(t * 1.6 + cell * 40.0))) * 0.30;
  }

  float bd, barc;
  rectOutline(p, hp, bd, barc);
  float perim = 4.0 * (hp.x + hp.y);
  float uPer  = barc / perim;
  col += acc * (1.0 - smoothstep(0.0, 0.0028, bd)) * 0.32;
  col += acc * (1.0 - smoothstep(0.0, 0.010, bd)) * 0.045;

  float marks = smoothstep(0.60, 0.95, hash11(floor(uPer * 150.0) + 3.0));
  float markMask = (1.0 - smoothstep(0.004, 0.012, bd)) * (1.0 - smoothstep(0.035, 0.040, bd));
  col += acc * marks * markMask * (0.16 + 0.10 * sin(t * 1.4 + uPer * 40.0));

  float pos = fract(t / 13.0);
  float d1 = abs(fract(uPer - pos + 0.5) - 0.5);
  float d2 = abs(fract(uPer - fract(pos + 0.5) + 0.5) - 0.5);
  float edge = 1.0 - smoothstep(0.0, 0.008, bd);
  col += acc * exp(-d1 * 140.0) * edge * 0.45;
  col += acc * exp(-d2 * 110.0) * edge * 0.25;

  float C = 0.56;
  float cmask = 1.0 - smoothstep(C * 0.70, C * 1.20, length(cu));
  float clip  = smoothstep(-0.004, 0.0015, cu.x) * smoothstep(-0.004, 0.0015, cu.y);
  float breathe = 0.65 + 0.35 * sin(t * 1.05);

  vec3 acc3 = vec3(0.0);

  if (cmask > 0.002) {
    float wedge = triSDF(cu - vec2(0.085, 0.085), 0.108);
    float wedgeFill = 1.0 - smoothstep(0.0, 0.020, wedge);
    acc3 += acc * wedgeFill * 0.04 * breathe;
    acc3 += acc * (1.0 - smoothstep(0.0, 0.0035, abs(wedge))) * (0.45 + 0.35 * breathe);

    for (int i = 0; i < 3; i++) {
      float fi = float(i);
      float angleShift = (hash11(cornerId * 13.71 + fi * 7.39) - 0.5) * 0.65;
      float ang = 0.62 + fi * 0.72 + angleShift;
      vec2  dv = vec2(cos(ang), sin(ang));
      vec2  nv = vec2(-dv.y, dv.x);
      float along  = dot(cu, dv);
      float across = dot(cu, nv);
      float ph = fract(along * 0.85 - t * (0.42 + 0.22 * fi));
      float bandW = exp(-abs(across) * (200.0 - 20.0 * fi));
      acc3 += acc * bandW * smoothstep(0.0, 0.18, ph) * (1.0 - smoothstep(0.52, 1.0, ph)) * 0.22;
    }

    float tg = abs(triSDF(cu - vec2(0.115, 0.115), 0.135));
    acc3 += acc * (1.0 - smoothstep(0.0, 0.0028, tg)) * 0.22;

    acc3 += acc * exp(-length(cu) * 42.0) * 0.40 * (0.7 + 0.3 * sin(t * 2.3));
  }

  col += acc3 * cmask * clip;

  vec2 c0 = hp - vec2(0.060, 0.060);
  float d00 = min(min(min(abs(p.x - c0.x), abs(p.x + c0.x)),
                      abs(p.y - c0.y)), abs(p.y + c0.y));
  float line00 = 1.0 - smoothstep(0.0, 0.0022, d00);
  col += acc * (line00 * 0.16 + exp(-d00 * 120.0) * 0.04) * (0.5 + 0.5 * sin(t * 1.55));

  col *= 0.98 + 0.02 * hash21(px * 0.7 + fract(t) * 53.1);

  float centerDark = smoothstep(0.20, 0.85, R);
  col *= mix(0.85, 1.0, centerDark);

  col = max(col, vec3(0.0));
  col = 1.0 - exp(-col * 1.15);
  col = pow(col, vec3(0.55));

  gl_FragColor = vec4(col, 0.92);
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
    id: 'corner-pulse',
    name: 'Corner Pulse · 角标脉冲（游戏内主风格）',
    fragmentShader: shaderPresets.cornerPulse,
  },
  {
    id: 'prism-lattice',
    name: 'Prism Lattice · 棱镜栅格（游戏内备选）',
    fragmentShader: shaderPresets.prismLattice,
  },
  {
    id: 'soft-vignette',
    name: 'Soft Vignette',
    fragmentShader: shaderPresets.vignette,
  },
];
