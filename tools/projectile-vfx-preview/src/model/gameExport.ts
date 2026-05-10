import type { BlendMode, Rgba, RibbonWaveType, TrailDecorationRenderMode } from './preset';

export interface GameProjectileVfxPreset {
  name: string;
  trailEntities: GameTrailEntityConfig[];
  hooks: GameProjectileVfxHookConfig[];
  lifecycle: GameProjectileVfxLifecycleConfig;
  samplePolicy: GameProjectileSamplePolicy;
}

export interface GameTrailEntityConfig {
  id: string;
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
  waveAmplitude: number;
  waveFrequency: number;
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
  stops: GameTrailDecorationGradientStop[];
}

export interface GameTrailDecorationGradientStop {
  offset: number;
  color: Rgba;
}

export interface GameProjectileVfxHookConfig {
  id: string;
  kind: 'onFire' | 'onAdvance' | 'onHit' | 'onExpire';
}

export interface GameProjectileVfxLifecycleConfig {
  fadeInSeconds: number;
  fadeOutSeconds: number;
}

export interface GameProjectileSamplePolicy {
  mode: 'projectile-history';
  maxSamples: number;
  minSampleDistance: number;
}
