import type {
  GameProjectileVfxPreset,
  GameTrailDecorationGradientStop,
  GameTrailEntityConfig,
  GameTrailRibbonDecorationConfig,
} from '../model/gameExport';
import type {
  BoxUtilPreviewPreset,
  TrailDecorationGradientStop,
  TrailEntityConfig,
  TrailRibbonDecorationConfig,
} from '../model/preset';

export function toGameExportPreset(preset: BoxUtilPreviewPreset): GameProjectileVfxPreset {
  return {
    name: preset.name,
    trailEntities: preset.trailEntities.map(toGameTrailEntityConfig),
    hooks: [],
    lifecycle: {
      fadeInSeconds: 0,
      fadeOutSeconds: 0.15,
    },
    samplePolicy: {
      mode: 'projectile-history',
      maxSamples: 96,
      minSampleDistance: 2,
    },
  };
}

export function serializeGameExportPreset(preset: BoxUtilPreviewPreset): string {
  return `${JSON.stringify(toGameExportPreset(preset), null, 2)}\n`;
}

function toGameTrailEntityConfig(entity: TrailEntityConfig): GameTrailEntityConfig {
  return {
    id: entity.id,
    startColor: entity.startColor,
    endColor: entity.endColor,
    startEmissive: entity.startEmissive,
    endEmissive: entity.endEmissive,
    startWidth: entity.startWidth,
    endWidth: entity.endWidth,
    texturePixels: entity.texturePixels,
    textureSpeed: entity.textureSpeed,
    uvOffset: entity.uvOffset,
    fillStartAlpha: entity.fillStartAlpha,
    fillEndAlpha: entity.fillEndAlpha,
    fillStartFactor: entity.fillStartFactor,
    fillEndFactor: entity.fillEndFactor,
    jitterPower: entity.jitterPower,
    flick: entity.flick,
    syncFlick: entity.syncFlick,
    stripLineMode: entity.stripLineMode,
    flowWhenPaused: entity.flowWhenPaused,
    flickWhenPaused: entity.flickWhenPaused,
    flickMixValue: entity.flickMixValue,
    flickerSyncCode: entity.flickerSyncCode,
    blendMode: entity.blendMode,
    ribbonDecorations: entity.ribbonDecorations.map(toGameTrailRibbonDecorationConfig),
  };
}

function toGameTrailRibbonDecorationConfig(decoration: TrailRibbonDecorationConfig): GameTrailRibbonDecorationConfig {
  return {
    id: decoration.id,
    enabled: decoration.enabled,
    renderMode: decoration.renderMode,
    startOffset: decoration.startOffset,
    endOffset: decoration.endOffset,
    thickness: decoration.thickness,
    alphaScale: decoration.alphaScale,
    lengthScale: decoration.lengthScale,
    nodeCountScale: decoration.nodeCountScale,
    waveAmplitude: decoration.waveAmplitude,
    waveFrequency: decoration.waveFrequency,
    waveSpeed: decoration.waveSpeed,
    waveType: decoration.waveType,
    noiseScale: decoration.noiseScale,
    blur: decoration.blur,
    startColor: decoration.startColor,
    endColor: decoration.endColor,
    color: decoration.color,
    colorGradient: {
      enabled: decoration.colorGradient.enabled,
      stops: decoration.colorGradient.stops.map(toGameTrailDecorationGradientStop),
    },
  };
}

function toGameTrailDecorationGradientStop(stop: TrailDecorationGradientStop): GameTrailDecorationGradientStop {
  return {
    offset: stop.offset,
    color: stop.color,
  };
}
