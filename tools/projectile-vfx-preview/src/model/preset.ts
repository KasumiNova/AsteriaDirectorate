export type Vec2 = [number, number];
export type Rgba = [number, number, number, number];
export type BlendMode = 'normal' | 'additive';
export type TrailDecorationRenderMode = 'byNodeCount' | 'byLength';
export type RibbonWaveType = 'sine' | 'noise' | 'zigzag';

export interface TrailDecorationGradientStop {
  offset: number;
  color: Rgba;
}

export interface TrailDecorationColorGradient {
  enabled: boolean;
  stops: TrailDecorationGradientStop[];
}

export interface TrailRibbonDecorationConfig {
  id: string;
  enabled: boolean;
  renderMode: TrailDecorationRenderMode;
  startOffset: number;
  endOffset: number;
  thickness: number;
  alphaScale: number;
  lengthScale: number;
  nodeCountScale: number;
  waveAmplitude: number;
  waveFrequency: number;
  waveSpeed: number;
  waveType: RibbonWaveType;
  noiseScale: number;
  blur: number;
  startColor: Rgba;
  endColor: Rgba;
  color: Rgba;
  colorGradient: TrailDecorationColorGradient;
}

export interface TrailNode {
  position: Vec2;
  age?: number;
}

export interface TrailEntityConfig {
  id: string;
  nodes: TrailNode[];
  startColor: Rgba;
  endColor: Rgba;
  startEmissive: Rgba;
  endEmissive: Rgba;
  startWidth: number;
  endWidth: number;
  texturePixels: number;
  textureSpeed: number;
  uvOffset: number;
  fillStartAlpha: number;
  fillEndAlpha: number;
  fillStartFactor: number;
  fillEndFactor: number;
  jitterPower: number;
  flick: boolean;
  syncFlick: boolean;
  stripLineMode: boolean;
  flowWhenPaused: boolean;
  flickWhenPaused: boolean;
  flickMixValue: number;
  flickerSyncCode: number;
  blendMode: BlendMode;
  ribbonDecorations: TrailRibbonDecorationConfig[];
}

export interface TimelineConfig {
  fps: number;
  durationSeconds: number;
}

export interface PreviewCameraConfig {
  center: Vec2;
  zoom: number;
}

export interface SimulationConfig {
  projectileVelocity: Vec2;
  loop: boolean;
  /** 弯曲轨迹的振幅（像素）。0 = 直线飞行。 */
  curveAmount: number;
  /** 弯曲轨迹的频率（Hz，每秒完整振荡次数）。 */
  curveFrequency: number;
}

export interface BoxUtilPreviewPreset {
  name: string;
  trailEntities: TrailEntityConfig[];
  timeline: TimelineConfig;
  previewCamera: PreviewCameraConfig;
  simulation: SimulationConfig;
}

export function createDefaultTrailEntityConfig(id = 'astd_default_trail'): TrailEntityConfig {
  return {
    id,
    nodes: [
      { position: [-420, 32], age: 1 },
      { position: [-360, 30], age: 0.92 },
      { position: [-300, 26], age: 0.84 },
      { position: [-240, 22], age: 0.76 },
      { position: [-180, 17], age: 0.68 },
      { position: [-120, 13], age: 0.58 },
      { position: [-60, 9], age: 0.48 },
      { position: [0, 6], age: 0.38 },
      { position: [70, 3], age: 0.28 },
      { position: [140, 1], age: 0.2 },
      { position: [220, 0], age: 0.13 },
      { position: [300, -2], age: 0.06 },
      { position: [380, -4], age: 0 },
    ],
    startColor: [0.92, 0.28, 0.82, 0.92],
    endColor: [0.22, 0.04, 0.18, 0.06],
    startEmissive: [1, 0.95, 0.98, 1],
    endEmissive: [0.45, 0.04, 0.2, 0.16],
    startWidth: 48,
    endWidth: 4,
    texturePixels: 96,
    textureSpeed: 0.9,
    uvOffset: 0,
    fillStartAlpha: 0.84,
    fillEndAlpha: 0.03,
    fillStartFactor: 0.02,
    fillEndFactor: 0.12,
    jitterPower: 0,
    flick: false,
    syncFlick: false,
    stripLineMode: true,
    flowWhenPaused: true,
    flickWhenPaused: true,
    flickMixValue: 0,
    flickerSyncCode: 17,
    blendMode: 'additive',
    ribbonDecorations: [createDefaultTrailRibbonDecorationConfig()],
  };
}

export function createDefaultTrailRibbonDecorationConfig(id = 'astd_default_ribbon_0'): TrailRibbonDecorationConfig {
  return {
    id,
    enabled: true,
    renderMode: 'byLength',
    startOffset: -18,
    endOffset: 10,
    thickness: 0.16,
    alphaScale: 0.28,
    lengthScale: 1,
    nodeCountScale: 1,
    waveAmplitude: 1.35,
    waveFrequency: 1.1,
    waveSpeed: 1,
    waveType: 'sine',
    noiseScale: 4.0,
    blur: 9,
    startColor: [0.92, 0.28, 0.82, 0.92],
    endColor: [0.22, 0.04, 0.18, 0.06],
    color: [0.92, 0.28, 0.82, 0.92],
    colorGradient: {
      enabled: false,
      stops: [],
    },
  };
}

export function createDefaultPreset(): BoxUtilPreviewPreset {
  return {
    name: 'ASTD Default TrailEntity Preview',
    trailEntities: [createDefaultTrailEntityConfig()],
    timeline: {
      fps: 60,
      durationSeconds: 1.25,
    },
    previewCamera: {
      center: [0, 0],
      zoom: 1,
    },
    simulation: {
      projectileVelocity: [560, 0],
      loop: false,
      curveAmount: 0.0,
      curveFrequency: 0.8,
    },
  };
}
