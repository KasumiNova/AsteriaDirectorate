import {
  BoxUtilPreviewPreset,
  ProjectileVfxGlowLayerConfig,
  ProjectileVfxHeadLayerConfig,
  ProjectileVfxMistLayerConfig,
  ProjectileVfxSideWispLayerConfig,
  Rgba,
  RibbonWaveType,
  ProjectileVfxLifecycleConfig,
  ProjectileVfxSamplingPolicyConfig,
  TrailDecorationColorGradient,
  TrailDecorationGradientStop,
  TrailDecorationRenderMode,
  TrailEntityConfig,
  TrailNode,
  TrailRibbonDecorationConfig,
  Vec2,
  createDefaultPreset,
  createDefaultTrailRibbonDecorationConfig,
} from './preset';

export interface ParsePresetError {
  path: string;
  message: string;
}

export type ParsePresetResult =
  | { ok: true; preset: BoxUtilPreviewPreset }
  | { ok: false; errors: ParsePresetError[] };

export function parsePresetJson(input: string): ParsePresetResult {
  let parsed: unknown;
  try {
    parsed = JSON.parse(input);
  } catch (error) {
    return {
      ok: false,
      errors: [{ path: '$', message: error instanceof Error ? error.message : 'Malformed JSON' }],
    };
  }

  if (!isRecord(parsed)) {
    return { ok: false, errors: [{ path: '$', message: 'Preset root must be an object' }] };
  }

  const defaults = createDefaultPreset();
  const errors: ParsePresetError[] = [];
  const root = parsed;
  const trailEntitiesValue = root.trailEntities ?? [];

  if (!Array.isArray(trailEntitiesValue)) {
    errors.push({ path: 'trailEntities', message: 'trailEntities must be an array' });
  }

  const trailEntities = Array.isArray(trailEntitiesValue)
    ? trailEntitiesValue.map((value, index) => parseTrailEntity(value, index, errors)).filter((value): value is TrailEntityConfig => value !== null)
    : [];

  if (errors.length > 0) {
    return { ok: false, errors };
  }

  return {
    ok: true,
    preset: {
      ...defaults,
      ...root,
      name: typeof root.name === 'string' ? root.name : defaults.name,
      trailEntities,
      headLayers: mergeLayerArray<ProjectileVfxHeadLayerConfig>(root.headLayers, defaults.headLayers),
      glowLayers: mergeLayerArray<ProjectileVfxGlowLayerConfig>(root.glowLayers, defaults.glowLayers),
      mistLayers: mergeLayerArray<ProjectileVfxMistLayerConfig>(root.mistLayers, defaults.mistLayers, mergeMistLayer),
      sideWispLayers: mergeLayerArray<ProjectileVfxSideWispLayerConfig>(root.sideWispLayers, defaults.sideWispLayers),
      ribbonDecorations: mergeRibbonDecorations(root.ribbonDecorations, defaults.ribbonDecorations, errors),
      lifecycle: mergeObject<ProjectileVfxLifecycleConfig>(root.lifecycle, defaults.lifecycle),
      samplingPolicy: mergeObject<ProjectileVfxSamplingPolicyConfig>(root.samplingPolicy, defaults.samplingPolicy),
      timeline: isRecord(root.timeline) ? { ...defaults.timeline, ...root.timeline } : defaults.timeline,
      previewCamera: isRecord(root.previewCamera) ? { ...defaults.previewCamera, ...root.previewCamera } : defaults.previewCamera,
      simulation: isRecord(root.simulation) ? { ...defaults.simulation, ...root.simulation } : defaults.simulation,
    },
  };
}

export function formatPresetJson(preset: BoxUtilPreviewPreset): string {
  return `${JSON.stringify(preset, null, 2)}\n`;
}

function parseTrailEntity(value: unknown, index: number, errors: ParsePresetError[]): TrailEntityConfig | null {
  const path = `trailEntities[${index}]`;
  if (!isRecord(value)) {
    errors.push({ path, message: 'Trail entity must be an object' });
    return null;
  }

  const fallback = createDefaultPreset().trailEntities[0];
  const nodesValue = value.nodes ?? [];
  const nodes = Array.isArray(nodesValue)
    ? nodesValue.map((node, nodeIndex) => parseTrailNode(node, `${path}.nodes[${nodeIndex}]`, errors)).filter((node): node is TrailNode => node !== null)
    : [];
  if (!Array.isArray(nodesValue)) {
    errors.push({ path: `${path}.nodes`, message: 'nodes must be an array' });
  }

  const startColor = readRgbaOrFallback(value.startColor, `${path}.startColor`, errors, fallback.startColor);
  const endColor = readRgbaOrFallback(value.endColor, `${path}.endColor`, errors, fallback.endColor);
  const startEmissive = readRgbaOrFallback(value.startEmissive, `${path}.startEmissive`, errors, fallback.startEmissive);
  const endEmissive = readRgbaOrFallback(value.endEmissive, `${path}.endEmissive`, errors, fallback.endEmissive);
  const ribbonDecorationsValue = value.ribbonDecorations ?? fallback.ribbonDecorations;

  const numberFields = [
    'startWidth',
    'endWidth',
    'texturePixels',
    'textureSpeed',
    'uvOffset',
    'fillStartAlpha',
    'fillEndAlpha',
    'fillStartFactor',
    'fillEndFactor',
    'jitterPower',
    'flickMixValue',
    'flickerSyncCode',
  ] as const;
  for (const field of numberFields) {
    if (!isFiniteNumber(value[field])) {
      errors.push({ path: `${path}.${field}`, message: `${field} must be a finite number` });
    }
  }

  if (errors.some((error) => error.path.startsWith(path))) {
    return null;
  }

  return {
    ...fallback,
    ...value,
    id: typeof value.id === 'string' ? value.id : fallback.id,
    nodes,
    startColor: startColor as Rgba,
    endColor: endColor as Rgba,
    startEmissive: startEmissive as Rgba,
    endEmissive: endEmissive as Rgba,
    blendMode: value.blendMode === 'normal' || value.blendMode === 'additive' ? value.blendMode : fallback.blendMode,
    ribbonDecorations: Array.isArray(ribbonDecorationsValue)
      ? ribbonDecorationsValue.map((item, ribbonIndex) => parseTrailRibbonDecoration(item, `${path}.ribbonDecorations[${ribbonIndex}]`, errors)).filter((item): item is NonNullable<typeof item> => item !== null)
      : fallback.ribbonDecorations,
  };
}

function mergeLayerArray<T extends { id: string }>(value: unknown, defaults: T[], mergeItem: (value: Record<string, unknown>, fallback: T) => T = mergeLayerItem): T[] {
  if (!Array.isArray(value)) {
    return defaults;
  }

  return value.map((item, index) => {
    const fallback = defaults[index % defaults.length];
    return isRecord(item) ? mergeItem(item, fallback) : fallback;
  });
}

function mergeLayerItem<T extends { id: string }>(value: Record<string, unknown>, fallback: T): T {
  return {
    ...fallback,
    ...value,
    id: typeof value.id === 'string' ? value.id : fallback.id,
  } as T;
}

function mergeMistLayer(value: Record<string, unknown>, fallback: ProjectileVfxMistLayerConfig): ProjectileVfxMistLayerConfig {
  return {
    ...fallback,
    ...value,
    id: typeof value.id === 'string' ? value.id : fallback.id,
    rxRange: mergeObject(value.rxRange, fallback.rxRange),
    ryRange: mergeObject(value.ryRange, fallback.ryRange),
    alphaRange: mergeObject(value.alphaRange, fallback.alphaRange),
  };
}

function mergeRibbonDecorations(value: unknown, defaults: TrailRibbonDecorationConfig[], errors: ParsePresetError[]): TrailRibbonDecorationConfig[] {
  if (!Array.isArray(value)) {
    return defaults;
  }

  return value
    .map((item, index) => parseTrailRibbonDecoration(item, `ribbonDecorations[${index}]`, errors))
    .filter((item): item is TrailRibbonDecorationConfig => item !== null);
}

function mergeObject<T extends object>(value: unknown, fallback: T): T {
  return isRecord(value) ? { ...fallback, ...value } : fallback;
}

function parseTrailRibbonDecoration(value: unknown, path: string, errors: ParsePresetError[]) {
  if (!isRecord(value)) {
    errors.push({ path, message: 'Ribbon decoration must be an object' });
    return null;
  }

  const fallback = createDefaultTrailRibbonDecorationConfig();
  const color = readRgbaOrFallback(value.color, `${path}.color`, errors, fallback.color);
  const startColor = readRgbaOrFallback(value.startColor, `${path}.startColor`, errors, fallback.startColor);
  const endColor = readRgbaOrFallback(value.endColor, `${path}.endColor`, errors, fallback.endColor);
  const gradientValue = value.colorGradient;

  const stops = Array.isArray((gradientValue as { stops?: unknown } | undefined)?.stops)
    ? ((gradientValue as { stops?: unknown }).stops as unknown[])
        .map((stop, index) => parseTrailRibbonGradientStop(stop, `${path}.colorGradient.stops[${index}]`, errors))
        .filter((stop): stop is TrailDecorationGradientStop => stop !== null)
    : fallback.colorGradient.stops;

  return {
    ...fallback,
    ...value,
    id: typeof value.id === 'string' ? value.id : fallback.id,
    enabled: typeof value.enabled === 'boolean' ? value.enabled : fallback.enabled,
    renderMode: value.renderMode === 'byNodeCount' || value.renderMode === 'byLength' ? value.renderMode as TrailDecorationRenderMode : fallback.renderMode,
    waveAmplitude: readFiniteNumber(value.waveAmplitude, value.amplitude, fallback.waveAmplitude),
    waveFrequency: readFiniteNumber(value.waveFrequency, value.frequency, fallback.waveFrequency),
    waveType: value.waveType === 'sine' || value.waveType === 'noise' || value.waveType === 'zigzag' ? value.waveType as RibbonWaveType : fallback.waveType,
    noiseScale: isFiniteNumber(value.noiseScale) ? value.noiseScale : fallback.noiseScale,
    startColor: startColor as Rgba,
    endColor: endColor as Rgba,
    color: color as Rgba,
    colorGradient: {
      enabled: typeof (gradientValue as TrailDecorationColorGradient | undefined)?.enabled === 'boolean'
        ? (gradientValue as TrailDecorationColorGradient).enabled
        : fallback.colorGradient.enabled,
      stops,
    },
  };
}

function parseTrailRibbonGradientStop(value: unknown, path: string, errors: ParsePresetError[]) {
  if (!isRecord(value)) {
    errors.push({ path, message: 'Gradient stop must be an object' });
    return null;
  }

  const color = readRgba(value.color, `${path}.color`, errors);
  if (!isFiniteNumber(value.offset)) {
    errors.push({ path: `${path}.offset`, message: 'offset must be a finite number' });
    return null;
  }

  if (!color) {
    return null;
  }

  return {
    offset: value.offset,
    color,
  };
}

function parseTrailNode(value: unknown, path: string, errors: ParsePresetError[]): TrailNode | null {
  if (!isRecord(value)) {
    errors.push({ path, message: 'Trail node must be an object' });
    return null;
  }

  const position = readVec2(value.position, `${path}.position`, errors);
  if (!position) {
    return null;
  }

  return {
    position,
    age: isFiniteNumber(value.age) ? value.age : undefined,
  };
}

function readRgba(value: unknown, path: string, errors: ParsePresetError[]): Rgba | null {
  if (!Array.isArray(value) || value.length !== 4 || !value.every(isFiniteNumber)) {
    errors.push({ path, message: 'RGBA must be four finite numbers' });
    return null;
  }

  return value as Rgba;
}

function readRgbaOrFallback(value: unknown, path: string, errors: ParsePresetError[], fallback: Rgba): Rgba {
  if (value === undefined || value === null) {
    return fallback;
  }

  const parsed = readRgba(value, path, errors);
  return parsed ?? fallback;
}

function readVec2(value: unknown, path: string, errors: ParsePresetError[]): Vec2 | null {
  if (Array.isArray(value) && value.length === 2 && value.every(isFiniteNumber)) {
    return value as Vec2;
  }

  if (isRecord(value) && isFiniteNumber(value.x) && isFiniteNumber(value.y)) {
    return [value.x, value.y];
  }

  errors.push({ path, message: 'Vec2 must be [x, y] or { x, y } with finite numbers' });
  return null;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isFiniteNumber(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value);
}

function readFiniteNumber(primary: unknown, secondary: unknown, fallback: number): number {
  if (isFiniteNumber(primary)) {
    return primary;
  }
  if (isFiniteNumber(secondary)) {
    return secondary;
  }
  return fallback;
}
