import type {
  BlendMode,
  ProjectileVfxAnchorMode,
  ProjectileVfxGlowLayerConfig,
  ProjectileVfxHeadLayerConfig,
  ProjectileVfxMistLayerConfig,
  ProjectileVfxOrientationMode,
  ProjectileVfxSideWispLayerConfig,
  Rgba,
  RibbonWaveType,
  TrailDecorationRenderMode,
} from './preset';

export interface GameProjectileVfxPreset {
  id: string;
  name: string;
  trailEntities: GameTrailEntityConfig[];
  headLayers: ProjectileVfxHeadLayerConfig[];
  glowLayers: ProjectileVfxGlowLayerConfig[];
  mistLayers: ProjectileVfxMistLayerConfig[];
  sideWispLayers: ProjectileVfxSideWispLayerConfig[];
  ribbonDecorations: GameTrailRibbonDecorationConfig[];
  hooks: GameProjectileVfxHookConfig[];
  lifecycle: GameProjectileVfxLifecycleConfig;
  samplingPolicy: GameProjectileSamplePolicy;
}

export interface GameTrailEntityConfig {
  id: string;
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
  ribbonDecorations: GameTrailRibbonDecorationConfig[];
}

export interface GameTrailRibbonDecorationConfig {
  id: string;
  enabled: boolean;
  renderMode: TrailDecorationRenderMode;
  startOffset: number;
  endOffset: number;
  thickness: number;
  alphaScale: number;
  lengthScale: number;
  nodeCountScale: number;
  amplitude: number;
  frequency: number;
  waveSpeed: number;
  waveType: RibbonWaveType;
  noiseScale: number;
  blur: number;
  startColor: Rgba;
  endColor: Rgba;
  color: Rgba;
  colorGradient: GameTrailDecorationColorGradient;
}

export interface GameTrailDecorationColorGradient {
  enabled: boolean;
  stops: GameTrailDecorationColorStop[];
}

export interface GameTrailDecorationColorStop {
  offset: number;
  color: Rgba;
}

export interface GameProjectileVfxHookConfig {
  id: string;
  kind: 'onFire' | 'onAdvance' | 'onHit' | 'onExpire';
}

export interface GameProjectileVfxLifecycleConfig {
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

export interface GameProjectileSamplePolicy {
  historyFps: number;
  maxHistoryNodes: number;
  minDistancePerNode: number;
  smoothingPasses: number;
  distanceWindow: number;
}
