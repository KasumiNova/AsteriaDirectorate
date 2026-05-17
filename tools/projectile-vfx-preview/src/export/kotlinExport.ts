import type {
	GameProjectileSamplePolicy,
	GameProjectileVfxHookConfig,
	GameProjectileVfxLifecycleConfig,
	GameProjectileVfxPreset,
	GameTrailDecorationColorStop,
	GameTrailDecorationColorGradient,
	GameTrailEntityConfig,
	GameTrailRibbonDecorationConfig,
} from '../model/gameExport';
import type {
	BoxUtilPreviewPreset,
	ColorStopConfig,
	FloatRangeConfig,
	ProjectileVfxGlowLayerConfig,
	ProjectileVfxHeadLayerConfig,
	ProjectileVfxMistLayerConfig,
	ProjectileVfxSideWispLayerConfig,
	Rgba,
} from '../model/preset';
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
		`        id = ${quoteKotlin(preset.id)},`,
		`        layers = emptyList(),`,
		`        samplingPolicy = ${emitSamplePolicy(preset.samplingPolicy)},`,
		`        fadePolicy = ASTDProjectileVfxFadePolicy(`,
		`            fadeInSeconds = ${formatFloat(preset.lifecycle.fadeInSeconds)},`,
		`            fadeOutSeconds = ${formatFloat(preset.lifecycle.fadeOutSeconds)},`,
		`            hitFadeOutSeconds = ${formatFloat(preset.lifecycle.fadeOutSeconds)},`,
		`            expireFadeOutSeconds = ${formatFloat(preset.lifecycle.fadeOutSeconds)},`,
		`        ),`,
		`        trailEntities = ${emitTrailEntities(preset.trailEntities)},`,
		`        headLayers = ${emitHeadLayers(preset.headLayers)},`,
		`        glowLayers = ${emitGlowLayers(preset.glowLayers)},`,
		`        mistLayers = ${emitMistLayers(preset.mistLayers)},`,
		`        sideWispLayers = ${emitSideWispLayers(preset.sideWispLayers)},`,
		`        ribbonDecorations = ${emitRibbonDecorations(preset.ribbonDecorations)},`,
		`        lifecycle = ${emitLifecycle(preset.lifecycle)},`,
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
		`            layerId = ${quoteKotlin(entity.id)},`,
		`            id = ${quoteKotlin(entity.id)},`,
		`            nodes = emptyList(),`,
		`            layerSpec = ${emitTrailLayer(entity)},`,
		`            layers = listOf(`,
		indent(emitTrailLayer(entity), 16),
		`            ),`,
		`            ribbonDecorations = ${emitRibbonDecorations(entity.ribbonDecorations)},`,
		`            orientationMode = ${emitOrientationMode(entity.orientationMode)},`,
		`            anchorMode = ${emitAnchorMode(entity.anchorMode)},`,
		'        )',
	].join('\n');
}

function emitTrailLayer(entity: GameTrailEntityConfig): string {
	return [
		'ASTDTrailLayerSpec(',
		`    width = ${formatFloat(entity.startWidth)},`,
		`    color = ${emitRgba(entity.startColor)},`,
		`    length = ${formatFloat(entity.length)},`,
		`    diffuseSpritePath = ${quoteKotlin(entity.diffuseSpritePath)},`,
		`    emissiveSpritePath = ${quoteKotlin(entity.emissiveSpritePath)},`,
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
		`                amplitude = ${formatFloat(decoration.amplitude)},`,
		`                frequency = ${formatFloat(decoration.frequency)},`,
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

function emitRibbonGradientStop(stop: GameTrailDecorationColorStop): string {
	return [
		'ASTDTrailDecorationColorStopSpec(',
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
		'ASTDProjectileVfxLifecycleSpec(',
		`            durationSeconds = ${formatFloat(lifecycle.durationSeconds)},`,
		`            flightEndRatio = ${formatFloat(lifecycle.flightEndRatio)},`,
		`            dissolveStartRatio = ${formatFloat(lifecycle.dissolveStartRatio)},`,
		`            preDissolveFraction = ${formatFloat(lifecycle.preDissolveFraction)},`,
		`            projectileHeadSizeScale = ${formatFloat(lifecycle.projectileHeadSizeScale)},`,
		`            historySampleMultiplier = ${formatFloat(lifecycle.historySampleMultiplier)},`,
		`            historySmoothingPasses = ${Math.trunc(lifecycle.historySmoothingPasses)},`,
		`            ribbonWaveSoftening = ${formatFloat(lifecycle.ribbonWaveSoftening)},`,
		'        )',
	].join('\n');
}

function emitSamplePolicy(samplePolicy: GameProjectileSamplePolicy): string {
	return [
		'ASTDProjectileVfxSamplingPolicy(',
		`            historyFps = ${formatFloat(samplePolicy.historyFps)},`,
		`            maxHistoryNodes = ${Math.trunc(samplePolicy.maxHistoryNodes)},`,
		`            minDistancePerNode = ${formatFloat(samplePolicy.minDistancePerNode)},`,
		`            smoothingPasses = ${Math.trunc(samplePolicy.smoothingPasses)},`,
		`            distanceWindow = ${formatFloat(samplePolicy.distanceWindow)},`,
		'        )',
	].join('\n');
}

function emitHeadLayers(layers: ProjectileVfxHeadLayerConfig[]): string {
	return emitList(layers, emitHeadLayer);
}

function emitHeadLayer(layer: ProjectileVfxHeadLayerConfig): string {
	return [
		'ASTDProjectileVfxHeadLayerSpec(',
		`            id = ${quoteKotlin(layer.id)},`,
		`            enabled = ${layer.enabled},`,
		`            length = ${formatFloat(layer.length)},`,
		`            width = ${formatFloat(layer.width)},`,
		`            shoulderRatio = ${formatFloat(layer.shoulderRatio)},`,
		`            rearRatio = ${formatFloat(layer.rearRatio)},`,
		`            shellColorStart = ${emitRgba(layer.shellColorStart)},`,
		`            shellColorMid = ${emitRgba(layer.shellColorMid)},`,
		`            shellColorEnd = ${emitRgba(layer.shellColorEnd)},`,
		`            blur = ${formatFloat(layer.blur)},`,
		`            alphaScale = ${formatFloat(layer.alphaScale)},`,
		`            blendMode = ${quoteKotlin(layer.blendMode)},`,
		'        )',
	].join('\n');
}

function emitGlowLayers(layers: ProjectileVfxGlowLayerConfig[]): string {
	return emitList(layers, emitGlowLayer);
}

function emitGlowLayer(layer: ProjectileVfxGlowLayerConfig): string {
	return [
		'ASTDProjectileVfxGlowLayerSpec(',
		`            id = ${quoteKotlin(layer.id)},`,
		`            enabled = ${layer.enabled},`,
		`            widthScale = ${formatFloat(layer.widthScale)},`,
		`            alphaScale = ${formatFloat(layer.alphaScale)},`,
		`            blur = ${formatFloat(layer.blur)},`,
		`            yOffset = ${formatFloat(layer.yOffset)},`,
		`            colorMixTail = ${formatFloat(layer.colorMixTail)},`,
		`            colorMixHead = ${formatFloat(layer.colorMixHead)},`,
		`            gradientStops = ${emitColorStops(layer.gradientStops)},`,
		'        )',
	].join('\n');
}

function emitMistLayers(layers: ProjectileVfxMistLayerConfig[]): string {
	return emitList(layers, emitMistLayer);
}

function emitMistLayer(layer: ProjectileVfxMistLayerConfig): string {
	return [
		'ASTDProjectileVfxMistLayerSpec(',
		`            id = ${quoteKotlin(layer.id)},`,
		`            enabled = ${layer.enabled},`,
		`            blobCount = ${Math.trunc(layer.blobCount)},`,
		`            lengthScale = ${formatFloat(layer.lengthScale)},`,
		`            widthScale = ${formatFloat(layer.widthScale)},`,
		`            rxRange = ${emitFloatRange(layer.rxRange)},`,
		`            ryRange = ${emitFloatRange(layer.ryRange)},`,
		`            alphaRange = ${emitFloatRange(layer.alphaRange)},`,
		`            noiseScale = ${formatFloat(layer.noiseScale)},`,
		`            driftSpeed = ${formatFloat(layer.driftSpeed)},`,
		`            colorStart = ${emitRgba(layer.colorStart)},`,
		`            colorEnd = ${emitRgba(layer.colorEnd)},`,
		'        )',
	].join('\n');
}

function emitSideWispLayers(layers: ProjectileVfxSideWispLayerConfig[]): string {
	return emitList(layers, emitSideWispLayer);
}

function emitSideWispLayer(layer: ProjectileVfxSideWispLayerConfig): string {
	return [
		'ASTDProjectileVfxSideWispLayerSpec(',
		`            id = ${quoteKotlin(layer.id)},`,
		`            enabled = ${layer.enabled},`,
		`            offsets = listOf(${layer.offsets.map(formatFloat).join(', ')}),`,
		`            widthScale = ${formatFloat(layer.widthScale)},`,
		`            alphaScale = ${formatFloat(layer.alphaScale)},`,
		`            blur = ${formatFloat(layer.blur)},`,
		`            lengthStartRatio = ${formatFloat(layer.lengthStartRatio)},`,
		`            lengthEndRatio = ${formatFloat(layer.lengthEndRatio)},`,
		`            color = ${emitRgba(layer.color)},`,
		'        )',
	].join('\n');
}

function emitColorStops(stops: ColorStopConfig[]): string {
	return emitList(stops, (stop) => [
		'ASTDColorStopSpec(',
		`            offset = ${formatFloat(stop.offset)},`,
		`            color = ${emitRgba(stop.color)},`,
		'        )',
	].join('\n'));
}

function emitFloatRange(range: FloatRangeConfig): string {
	return `ASTDFloatRangeSpec(min = ${formatFloat(range.min)}, max = ${formatFloat(range.max)})`;
}

function emitOrientationMode(value: string): string {
	if (value === 'projectileFacing') return 'ASTDProjectileVfxOrientationMode.ProjectileFacing';
	if (value === 'custom') return 'ASTDProjectileVfxOrientationMode.Custom';
	return 'ASTDProjectileVfxOrientationMode.ProjectileVelocity';
}

function emitAnchorMode(value: string): string {
	void value;
	return 'ASTDProjectileVfxAnchorMode.HeadLocked';
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
	return `ASTDColor(${value.map(formatFloat).join(', ')})`;
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
