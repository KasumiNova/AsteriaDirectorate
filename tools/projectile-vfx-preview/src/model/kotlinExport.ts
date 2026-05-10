import {
  BoxUtilPreviewPreset,
  PreviewCameraConfig,
  Rgba,
  TrailDecorationColorGradient,
  TrailDecorationGradientStop,
  TrailDecorationRenderMode,
  SimulationConfig,
  TrailEntityConfig,
  TrailRibbonDecorationConfig,
  TrailNode,
  Vec2,
} from './preset';

export interface KotlinExportOptions {
  objectName?: string;
  functionName?: string;
}

export function formatPresetKotlin(preset: BoxUtilPreviewPreset, options: KotlinExportOptions = {}): string {
  const objectName = options.objectName ?? 'ASTDProjectileVfxPreviewPreset';
  const functionName = options.functionName ?? 'create';

  return [
    `internal object ${objectName} {`,
    `    fun ${functionName}(): BoxUtilPreviewPreset = BoxUtilPreviewPreset(`,
    `        name = ${quoteKotlin(preset.name)},`,
    `        trailEntities = ${emitTrailEntities(preset.trailEntities)},`,
    `        timeline = ${emitTimeline(preset.timeline)},`,
    `        previewCamera = ${emitPreviewCamera(preset.previewCamera)},`,
    `        simulation = ${emitSimulation(preset.simulation)},`,
    `    )`,
    `}`,
    '',
  ].join('\n');
}

function emitTrailEntities(entities: TrailEntityConfig[]): string {
  return emitList(entities, emitTrailEntity);
}

function emitTrailEntity(entity: TrailEntityConfig): string {
  return [
    'TrailEntityConfig(',
    `            id = ${quoteKotlin(entity.id)},`,
    `            nodes = ${emitList(entity.nodes, emitTrailNode)},`,
    `            startColor = ${emitRgba(entity.startColor)},`,
    `            endColor = ${emitRgba(entity.endColor)},`,
    `            startEmissive = ${emitRgba(entity.startEmissive)},`,
    `            endEmissive = ${emitRgba(entity.endEmissive)},`,
    `            startWidth = ${formatNumber(entity.startWidth)}f,`,
    `            endWidth = ${formatNumber(entity.endWidth)}f,`,
    `            texturePixels = ${formatNumber(entity.texturePixels)}f,`,
    `            textureSpeed = ${formatNumber(entity.textureSpeed)}f,`,
    `            uvOffset = ${formatNumber(entity.uvOffset)}f,`,
    `            fillStartAlpha = ${formatNumber(entity.fillStartAlpha)}f,`,
    `            fillEndAlpha = ${formatNumber(entity.fillEndAlpha)}f,`,
    `            fillStartFactor = ${formatNumber(entity.fillStartFactor)}f,`,
    `            fillEndFactor = ${formatNumber(entity.fillEndFactor)}f,`,
    `            jitterPower = ${formatNumber(entity.jitterPower)}f,`,
    `            flick = ${entity.flick},`,
    `            syncFlick = ${entity.syncFlick},`,
    `            stripLineMode = ${entity.stripLineMode},`,
    `            flowWhenPaused = ${entity.flowWhenPaused},`,
    `            flickWhenPaused = ${entity.flickWhenPaused},`,
    `            flickMixValue = ${formatNumber(entity.flickMixValue)}f,`,
    `            flickerSyncCode = ${Math.trunc(entity.flickerSyncCode)},`,
    `            blendMode = ${quoteKotlin(entity.blendMode)},`,
    `            ribbonDecorations = ${emitRibbonDecorations(entity.ribbonDecorations)},`,
    '        )',
  ].join('\n');
}

function emitRibbonDecorations(entities: TrailRibbonDecorationConfig[]): string {
  return emitList(entities, emitRibbonDecoration);
}

function emitRibbonDecoration(entity: TrailRibbonDecorationConfig): string {
  return [
    'TrailRibbonDecorationConfig(',
    `                id = ${quoteKotlin(entity.id)},`,
    `                enabled = ${entity.enabled},`,
    `                renderMode = ${quoteKotlin(entity.renderMode)},`,
    `                startOffset = ${formatNumber(entity.startOffset)}f,`,
    `                endOffset = ${formatNumber(entity.endOffset)}f,`,
    `                thickness = ${formatNumber(entity.thickness)}f,`,
    `                alphaScale = ${formatNumber(entity.alphaScale)}f,`,
    `                lengthScale = ${formatNumber(entity.lengthScale)}f,`,
    `                nodeCountScale = ${formatNumber(entity.nodeCountScale)}f,`,
    `                waveAmplitude = ${formatNumber(entity.waveAmplitude)}f,`,
    `                waveFrequency = ${formatNumber(entity.waveFrequency)}f,`,
    `                waveSpeed = ${formatNumber(entity.waveSpeed)}f,`,
    `                waveType = ${quoteKotlin(entity.waveType)},`,
    `                noiseScale = ${formatNumber(entity.noiseScale)}f,`,
    `                blur = ${formatNumber(entity.blur)}f,`,
    `                startColor = ${emitRgba(entity.startColor)},`,
    `                endColor = ${emitRgba(entity.endColor)},`,
    `                color = ${emitRgba(entity.color)},`,
    `                colorGradient = ${emitRibbonGradient(entity.colorGradient)},`,
    '            )',
  ].join('\n');
}

function emitRibbonGradient(gradient: TrailDecorationColorGradient): string {
  return [
    'TrailDecorationColorGradient(',
    `                    enabled = ${gradient.enabled},`,
    `                    stops = ${emitList(gradient.stops, emitRibbonGradientStop)},`,
    '                )',
  ].join('\n');
}

function emitRibbonGradientStop(stop: TrailDecorationGradientStop): string {
  return [
    'TrailDecorationGradientStop(',
    `                        offset = ${formatNumber(stop.offset)}f,`,
    `                        color = ${emitRgba(stop.color)},`,
    '                    )',
  ].join('\n');
}

function emitTrailNode(node: TrailNode): string {
  return [
    'TrailNode(',
    `                position = ${emitVec2(node.position)},`,
    node.age === undefined ? undefined : `                age = ${formatNumber(node.age)}f,`,
    '            )',
  ].filter((line): line is string => Boolean(line)).join('\n');
}

function emitTimeline(config: BoxUtilPreviewPreset['timeline']): string {
  return [
    'TimelineConfig(',
    `            fps = ${formatNumber(config.fps)}f,`,
    `            durationSeconds = ${formatNumber(config.durationSeconds)}f,`,
    '        )',
  ].join('\n');
}

function emitPreviewCamera(config: PreviewCameraConfig): string {
  return [
    'PreviewCameraConfig(',
    `            center = ${emitVec2(config.center)},`,
    `            zoom = ${formatNumber(config.zoom)}f,`,
    '        )',
  ].join('\n');
}

function emitSimulation(config: SimulationConfig): string {
  return [
    'SimulationConfig(',
    `            projectileVelocity = ${emitVec2(config.projectileVelocity)},`,
    `            loop = ${config.loop},`,
    '        )',
  ].join('\n');
}

function emitList<T>(items: T[], mapper: (item: T) => string): string {
  if (items.length === 0) {
    return 'emptyList()';
  }

  const body = items.map((item) => indent(mapper(item), 8)).join(',\n');
  return [
    'listOf(',
    body,
    '        )',
  ].join('\n');
}

function emitVec2(value: Vec2): string {
  return `listOf(${formatNumber(value[0])}f, ${formatNumber(value[1])}f)`;
}

function emitRgba(value: Rgba): string {
  return `listOf(${value.map((component) => `${formatNumber(component)}f`).join(', ')})`;
}

function quoteKotlin(value: string): string {
  return `"${value.replace(/\\/g, '\\\\').replace(/"/g, '\\"')}"`;
}

function formatNumber(value: number): string {
  if (!Number.isFinite(value)) {
    return '0';
  }

  const normalized = Object.is(value, -0) ? 0 : value;
  if (Number.isInteger(normalized)) {
    return `${normalized}`;
  }

  return `${Number(normalized.toFixed(6)).toString()}`;
}

function indent(text: string, spaces: number): string {
  const prefix = ' '.repeat(spaces);
  return text.split('\n').map((line) => `${prefix}${line}`).join('\n');
}