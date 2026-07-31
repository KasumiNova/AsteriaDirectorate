export type Vec2 = [number, number];
export type Rgba = [number, number, number, number];
export type BlendMode = 'normal' | 'additive';
export type TrailDecorationRenderMode = 'byNodeCount' | 'byLength';
export type RibbonWaveType = 'sine' | 'noise' | 'zigzag';
export type ProjectileVfxOrientationMode = 'projectileVelocity' | 'projectileFacing' | 'custom';
export type ProjectileVfxAnchorMode = 'headLocked';

export interface ColorStopConfig {
  offset: number;
  color: Rgba;
}

export interface FloatRangeConfig {
  min: number;
  max: number;
}

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
  length: number;
  diffuseSpritePath: string;
  emissiveSpritePath: string;
  orientationMode: ProjectileVfxOrientationMode;
  anchorMode: ProjectileVfxAnchorMode;
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

export interface ProjectileVfxHeadLayerConfig {
  id: string;
  enabled: boolean;
  length: number;
  width: number;
  shoulderRatio: number;
  rearRatio: number;
  shellColorStart: Rgba;
  shellColorMid: Rgba;
  shellColorEnd: Rgba;
  blur: number;
  alphaScale: number;
  blendMode: BlendMode;
}

export interface ProjectileVfxGlowLayerConfig {
  id: string;
  enabled: boolean;
  widthScale: number;
  alphaScale: number;
  blur: number;
  yOffset: number;
  colorMixTail: number;
  colorMixHead: number;
  gradientStops: ColorStopConfig[];
}

export interface ProjectileVfxMistLayerConfig {
  id: string;
  enabled: boolean;
  blobCount: number;
  lengthScale: number;
  widthScale: number;
  rxRange: FloatRangeConfig;
  ryRange: FloatRangeConfig;
  alphaRange: FloatRangeConfig;
  noiseScale: number;
  driftSpeed: number;
  colorStart: Rgba;
  colorEnd: Rgba;
}

export interface ProjectileVfxSideWispLayerConfig {
  id: string;
  enabled: boolean;
  offsets: number[];
  widthScale: number;
  alphaScale: number;
  blur: number;
  lengthStartRatio: number;
  lengthEndRatio: number;
  color: Rgba;
}

export interface ProjectileVfxLifecycleConfig {
  durationSeconds: number;
  fadeInSeconds: number;
  fadeOutSeconds: number;
  flightEndRatio: number;
  dissolveStartRatio: number;
  preDissolveFraction: number;
  projectileHeadSizeScale: number;
  historySampleMultiplier: number;
  historySmoothingPasses: number;
  ribbonWaveSoftening: number;
  layoutReferenceWidth: number;
}

export interface ProjectileVfxSamplingPolicyConfig {
  historyFps: number;
  maxHistoryNodes: number;
  minDistancePerNode: number;
  distanceWindow: number;
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
  headLayers: ProjectileVfxHeadLayerConfig[];
  glowLayers: ProjectileVfxGlowLayerConfig[];
  mistLayers: ProjectileVfxMistLayerConfig[];
  sideWispLayers: ProjectileVfxSideWispLayerConfig[];
  ribbonDecorations: TrailRibbonDecorationConfig[];
  lifecycle: ProjectileVfxLifecycleConfig;
  samplingPolicy: ProjectileVfxSamplingPolicyConfig;
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
    length: 420,
    diffuseSpritePath: 'graphics/fx/beamcoreb.png',
    emissiveSpritePath: 'graphics/fx/beamfringeb.png',
    orientationMode: 'projectileVelocity',
    anchorMode: 'headLocked',
    startColor: [0.278431, 0.556863, 0.921569, 0.92],
    endColor: [0.039216, 0.141176, 0.219608, 0.06],
    startEmissive: [0.941176, 0.972549, 1, 1],
    endEmissive: [0.039216, 0.2, 0.458824, 0.16],
    startWidth: 40,
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
    startOffset: 0,
    endOffset: 0,
    thickness: 0.1,
    alphaScale: 0.28,
    lengthScale: 1,
    nodeCountScale: 1,
    waveAmplitude: 1.35,
    waveFrequency: 1.1,
    waveSpeed: 1,
    waveType: 'noise',
    noiseScale: 4.0,
    blur: 9,
    startColor: [0.890196, 0.921569, 0.933333, 0.92],
    endColor: [0.039216, 0.109804, 0.219608, 0.06],
    color: [1, 1, 1, 0.92],
    colorGradient: {
      enabled: false,
      stops: [],
    },
  };
}

export function createDefaultPreset(): BoxUtilPreviewPreset {
  const ribbonDecorations = [createDefaultTrailRibbonDecorationConfig()];
  return {
    name: 'ASTD Default TrailEntity Preview',
    trailEntities: [{ ...createDefaultTrailEntityConfig(), ribbonDecorations }],
    headLayers: [
      {
        id: 'astd_default_head_0',
        enabled: true,
        length: 138,
        width: 24,
        shoulderRatio: 0.5,
        rearRatio: 0.95,
        shellColorStart: [0.22, 0.04, 0.18, 0.08],
        shellColorMid: [0.72, 0.94, 1, 0.46],
        shellColorEnd: [1, 1, 1, 0.98],
        blur: 0.35,
        alphaScale: 1,
        blendMode: 'additive',
      },
    ],
    glowLayers: [
      { id: 'astd_default_glow_0', enabled: true, widthScale: 5.4, alphaScale: 0.18, blur: 34, yOffset: -0.36, colorMixTail: 0.52, colorMixHead: 0.44, gradientStops: [] },
      { id: 'astd_default_glow_1', enabled: true, widthScale: 3.2, alphaScale: 0.3, blur: 18, yOffset: 0.22, colorMixTail: 0.52, colorMixHead: 0.44, gradientStops: [] },
      { id: 'astd_default_glow_2', enabled: true, widthScale: 1.4, alphaScale: 0.58, blur: 7, yOffset: -0.08, colorMixTail: 0.22, colorMixHead: 1, gradientStops: [] },
      { id: 'astd_default_glow_3', enabled: true, widthScale: 0.62, alphaScale: 0.82, blur: 4, yOffset: 0, colorMixTail: 0.48, colorMixHead: 1, gradientStops: [] },
    ],
    mistLayers: [
      {
        id: 'astd_default_mist_0',
        enabled: true,
        blobCount: 52,
        lengthScale: 1,
        widthScale: 1,
        rxRange: { min: 2.4, max: 7.2 },
        ryRange: { min: 0.45, max: 1.8 },
        alphaRange: { min: 0.016, max: 0.075 },
        noiseScale: 5.2,
        driftSpeed: 0.32,
        colorStart: [0.22, 0.04, 0.18, 0.06],
        colorEnd: [1, 0.95, 0.98, 1],
      },
    ],
    sideWispLayers: [
      {
        id: 'astd_default_side_wisp_0',
        enabled: true,
        offsets: [-2.1, -1.36, 1.28, 2.0],
        widthScale: 0.2,
        alphaScale: 0.24,
        blur: 10,
        lengthStartRatio: 0.64,
        lengthEndRatio: 0.28,
        color: [0.45, 0.7, 1, 0.72],
      },
    ],
    ribbonDecorations,
    lifecycle: {
      durationSeconds: 1.25,
      fadeInSeconds: 0,
      fadeOutSeconds: 0.15,
      flightEndRatio: 0.6,
      dissolveStartRatio: 0.6,
      preDissolveFraction: 0.82,
      projectileHeadSizeScale: 1.5,
      historySampleMultiplier: 3,
      historySmoothingPasses: 3,
      ribbonWaveSoftening: 0.48,
      layoutReferenceWidth: 1846,
    },
    samplingPolicy: {
      historyFps: 60,
      maxHistoryNodes: 96,
      minDistancePerNode: 2,
      distanceWindow: 420,
    },
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
