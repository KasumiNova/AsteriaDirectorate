import aod7GamePresetJson from '../../../../contents/data/config/astd_projectile_vfx_presets/aod7_shot.json';
import type { GameProjectileVfxPreset, GameTrailEntityConfig, GameTrailRibbonDecorationConfig } from './gameExport';
import type { BoxUtilPreviewPreset, TrailEntityConfig, TrailRibbonDecorationConfig } from './preset';
import { createDefaultPreset } from './preset';

export const AOD7_PRESET_STORAGE_VERSION = 'aod7_shot:game-export-v1';

export function createAod7Preset(): BoxUtilPreviewPreset {
  return fromGameExportPreset(aod7GamePresetJson as unknown as GameProjectileVfxPreset);
}

export function fromGameExportPreset(gamePreset: GameProjectileVfxPreset): BoxUtilPreviewPreset {
  const defaults = createDefaultPreset();
  return {
    ...defaults,
    name: gamePreset.name,
    trailEntities: gamePreset.trailEntities.map((entity, index) => fromGameTrailEntity(entity, defaults.trailEntities[index % defaults.trailEntities.length])),
    headLayers: gamePreset.headLayers,
    glowLayers: gamePreset.glowLayers,
    mistLayers: gamePreset.mistLayers,
    sideWispLayers: gamePreset.sideWispLayers,
    ribbonDecorations: gamePreset.ribbonDecorations.map(fromGameRibbonDecoration),
    lifecycle: gamePreset.lifecycle,
    samplingPolicy: {
      historyFps: gamePreset.samplingPolicy.historyFps,
      maxHistoryNodes: gamePreset.samplingPolicy.maxHistoryNodes,
      minDistancePerNode: gamePreset.samplingPolicy.minDistancePerNode,
      distanceWindow: gamePreset.samplingPolicy.distanceWindow,
    },
  };
}

function fromGameTrailEntity(entity: GameTrailEntityConfig, fallback: TrailEntityConfig): TrailEntityConfig {
  return {
    ...fallback,
    ...entity,
    nodes: fallback.nodes,
    ribbonDecorations: entity.ribbonDecorations.map(fromGameRibbonDecoration),
  };
}

function fromGameRibbonDecoration(decoration: GameTrailRibbonDecorationConfig): TrailRibbonDecorationConfig {
  return {
    ...decoration,
    waveAmplitude: decoration.amplitude,
    waveFrequency: decoration.frequency,
  };
}
