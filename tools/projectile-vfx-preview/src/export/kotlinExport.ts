import type {
	GameProjectileSamplePolicy,
	GameProjectileVfxHookConfig,
	GameProjectileVfxLifecycleConfig,
	GameProjectileVfxPreset,
	GameTrailDecorationColorGradient,
	GameTrailDecorationGradientStop,
	GameTrailEntityConfig,
	GameTrailRibbonDecorationConfig,
} from '../model/gameExport';
import type { BoxUtilPreviewPreset, Rgba } from '../model/preset';
import { toGameExportPreset } from './gameExport';

export interface KotlinExportOptions {
	objectName?: string;
	functionName?: string;
}

export function formatPresetKotlin(preset: BoxUtilPreviewPreset, options: KotlinExportOptions = {}): string {
	return formatGamePresetKotlin(toGameExportPreset(preset), options);
}

function formatGamePresetKotlin(preset: GameProjectileVfxPreset, options: KotlinExportOptions): string {
	const objectName = options.objectName ?? 'ASTDProjectileVfxPresetExport';
	const functionName = options.functionName ?? 'create';

	return [
		`internal object ${objectName} {`,
		`    fun ${functionName}(): ASTDProjectileVfxPreset = ASTDProjectileVfxPreset(`,
		`        name = ${quoteKotlin(preset.name)},`,
		`        trailEntities = ${emitTrailEntities(preset.trailEntities)},`,
		`        hooks = ${emitHooks(preset.hooks)},`,
		`        lifecycle = ${emitLifecycle(preset.lifecycle)},`,
		`        samplePolicy = ${emitSamplePolicy(preset.samplePolicy)},`,
		`    )`,
		`}`,
		'',
	].join('\n');
}

function emitTrailEntities(entities: GameTrailEntityConfig[]): string {
	return emitList(entities, emitTrailEntity);
}

function emitTrailEntity(entity: GameTrailEntityConfig): string {
	return [
		'ASTDTrailEntitySpec(',
		`            id = ${quoteKotlin(entity.id)},`,
		`            layers = listOf(`,
		indent(emitTrailLayer(entity), 16),
		`            ),`,
		`            ribbonDecorations = ${emitRibbonDecorations(entity.ribbonDecorations)},`,
		'        )',
	].join('\n');
}

function emitTrailLayer(entity: GameTrailEntityConfig): string {
	return [
		'ASTDTrailLayerSpec(',
		`    startColor = ${emitRgba(entity.startColor)},`,
		`    endColor = ${emitRgba(entity.endColor)},`,
		`    startEmissive = ${emitRgba(entity.startEmissive)},`,
		`    endEmissive = ${emitRgba(entity.endEmissive)},`,
		`    startWidth = ${formatFloat(entity.startWidth)},`,
		`    endWidth = ${formatFloat(entity.endWidth)},`,
		`    texturePixels = ${formatFloat(entity.texturePixels)},`,
		`    textureSpeed = ${formatFloat(entity.textureSpeed)},`,
		`    uvOffset = ${formatFloat(entity.uvOffset)},`,
		`    fillStartAlpha = ${formatFloat(entity.fillStartAlpha)},`,
		`    fillEndAlpha = ${formatFloat(entity.fillEndAlpha)},`,
		`    fillStartFactor = ${formatFloat(entity.fillStartFactor)},`,
		`    fillEndFactor = ${formatFloat(entity.fillEndFactor)},`,
		`    jitterPower = ${formatFloat(entity.jitterPower)},`,
		`    flick = ${entity.flick},`,
		`    syncFlick = ${entity.syncFlick},`,
		`    stripLineMode = ${entity.stripLineMode},`,
		`    flowWhenPaused = ${entity.flowWhenPaused},`,
		`    flickWhenPaused = ${entity.flickWhenPaused},`,
		`    flickMixValue = ${formatFloat(entity.flickMixValue)},`,
		`    flickerSyncCode = ${Math.trunc(entity.flickerSyncCode)},`,
		`    blendMode = ${quoteKotlin(entity.blendMode)},`,
		')',
	].join('\n');
}

function emitRibbonDecorations(decorations: GameTrailRibbonDecorationConfig[]): string {
	return emitList(decorations, emitRibbonDecoration);
}

function emitRibbonDecoration(decoration: GameTrailRibbonDecorationConfig): string {
	return [
		'ASTDTrailRibbonDecorationSpec(',
		`                id = ${quoteKotlin(decoration.id)},`,
		`                enabled = ${decoration.enabled},`,
		`                renderMode = ${quoteKotlin(decoration.renderMode)},`,
		`                startOffset = ${formatFloat(decoration.startOffset)},`,
		`                endOffset = ${formatFloat(decoration.endOffset)},`,
		`                thickness = ${formatFloat(decoration.thickness)},`,
		`                alphaScale = ${formatFloat(decoration.alphaScale)},`,
		`                lengthScale = ${formatFloat(decoration.lengthScale)},`,
		`                nodeCountScale = ${formatFloat(decoration.nodeCountScale)},`,
		`                waveAmplitude = ${formatFloat(decoration.waveAmplitude)},`,
		`                waveFrequency = ${formatFloat(decoration.waveFrequency)},`,
		`                waveSpeed = ${formatFloat(decoration.waveSpeed)},`,
		`                waveType = ${quoteKotlin(decoration.waveType)},`,
		`                noiseScale = ${formatFloat(decoration.noiseScale)},`,
		`                blur = ${formatFloat(decoration.blur)},`,
		`                startColor = ${emitRgba(decoration.startColor)},`,
		`                endColor = ${emitRgba(decoration.endColor)},`,
		`                color = ${emitRgba(decoration.color)},`,
		`                colorGradient = ${emitRibbonGradient(decoration.colorGradient)},`,
		'            )',
	].join('\n');
}

function emitRibbonGradient(gradient: GameTrailDecorationColorGradient): string {
	return [
		'ASTDTrailDecorationColorGradientSpec(',
		`                    enabled = ${gradient.enabled},`,
		`                    stops = ${emitList(gradient.stops, emitRibbonGradientStop)},`,
		'                )',
	].join('\n');
}

function emitRibbonGradientStop(stop: GameTrailDecorationGradientStop): string {
	return [
		'ASTDTrailDecorationGradientStopSpec(',
		`                        offset = ${formatFloat(stop.offset)},`,
		`                        color = ${emitRgba(stop.color)},`,
		'                    )',
	].join('\n');
}

function emitHooks(hooks: GameProjectileVfxHookConfig[]): string {
	return emitList(hooks, emitHook);
}

function emitHook(hook: GameProjectileVfxHookConfig): string {
	return [
		'ASTDProjectileVfxHookSpec(',
		`            id = ${quoteKotlin(hook.id)},`,
		`            kind = ${quoteKotlin(hook.kind)},`,
		'        )',
	].join('\n');
}

function emitLifecycle(lifecycle: GameProjectileVfxLifecycleConfig): string {
	return [
		'ASTDProjectileLifecycleSpec(',
		`            fadeInSeconds = ${formatFloat(lifecycle.fadeInSeconds)},`,
		`            fadeOutSeconds = ${formatFloat(lifecycle.fadeOutSeconds)},`,
		'        )',
	].join('\n');
}

function emitSamplePolicy(samplePolicy: GameProjectileSamplePolicy): string {
	return [
		'ASTDProjectileSamplePolicySpec(',
		`            mode = ${quoteKotlin(samplePolicy.mode)},`,
		`            maxSamples = ${Math.trunc(samplePolicy.maxSamples)},`,
		`            minSampleDistance = ${formatFloat(samplePolicy.minSampleDistance)},`,
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

function emitRgba(value: Rgba): string {
	return `listOf(${value.map(formatFloat).join(', ')})`;
}

function quoteKotlin(value: string): string {
	return `"${value.replace(/\\/g, '\\\\').replace(/"/g, '\\"')}"`;
}

function formatFloat(value: number): string {
	if (!Number.isFinite(value)) {
		return '0f';
	}

	const normalized = Object.is(value, -0) ? 0 : value;
	if (Number.isInteger(normalized)) {
		return `${normalized}f`;
	}

	return `${Number(normalized.toFixed(6)).toString()}f`;
}

function indent(text: string, spaces: number): string {
	const prefix = ' '.repeat(spaces);
	return text.split('\n').map((line) => `${prefix}${line}`).join('\n');
}
