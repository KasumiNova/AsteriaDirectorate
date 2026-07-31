import type {
	GameProjectileSamplePolicy,
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
	TrailDecorationGradientStop,
	TrailEntityConfig,
	TrailRibbonDecorationConfig,
} from '../model/preset';

export interface KotlinExportOptions {
	objectName?: string;
	functionName?: string;
}

export function formatPresetKotlin(preset: BoxUtilPreviewPreset, options: KotlinExportOptions = {}): string {
	return formatGamePresetKotlin(toGameExportPreset(preset), options);
}

function toGameExportPreset(preset: BoxUtilPreviewPreset): GameProjectileVfxPreset {
	const ribbonDecorations = preset.ribbonDecorations.length > 0
		? preset.ribbonDecorations
		: preset.trailEntities.flatMap((entity) => entity.ribbonDecorations);
	return {
		id: 'aod7_shot',
		name: preset.name,
		trailEntities: preset.trailEntities.map(toGameTrailEntityConfig),
		headLayers: preset.headLayers,
		glowLayers: preset.glowLayers,
		mistLayers: preset.mistLayers,
		sideWispLayers: preset.sideWispLayers,
		ribbonDecorations: ribbonDecorations.map(toGameTrailRibbonDecorationConfig),
		hooks: [],
		lifecycle: {
			...preset.lifecycle,
		},
		samplingPolicy: {
			historyFps: preset.samplingPolicy.historyFps,
			maxHistoryNodes: preset.samplingPolicy.maxHistoryNodes,
			minDistancePerNode: preset.samplingPolicy.minDistancePerNode,
			smoothingPasses: preset.lifecycle.historySmoothingPasses,
			distanceWindow: preset.samplingPolicy.distanceWindow,
		},
	};
}

function toGameTrailEntityConfig(entity: TrailEntityConfig): GameTrailEntityConfig {
	return {
		id: entity.id,
		length: entity.length,
		diffuseSpritePath: entity.diffuseSpritePath,
		emissiveSpritePath: entity.emissiveSpritePath,
		orientationMode: entity.orientationMode,
		anchorMode: entity.anchorMode,
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
		amplitude: decoration.waveAmplitude,
		frequency: decoration.waveFrequency,
		waveSpeed: decoration.waveSpeed,
		waveType: decoration.waveType,
		noiseScale: decoration.noiseScale,
		blur: decoration.blur,
		startColor: decoration.startColor,
		endColor: decoration.endColor,
		color: decoration.color,
		colorGradient: {
			enabled: decoration.colorGradient.enabled,
			stops: decoration.colorGradient.stops.map(toGameTrailDecorationColorStop),
		},
	};
}

function toGameTrailDecorationColorStop(stop: TrailDecorationGradientStop): GameTrailDecorationColorStop {
	return {
		offset: stop.offset,
		color: stop.color,
	};
}

function formatGamePresetKotlin(preset: GameProjectileVfxPreset, options: KotlinExportOptions): string {
	const objectName = options.objectName ?? 'ASTDProjectileVfxPresetExport';
	const functionName = options.functionName ?? 'create';

	return [
		`internal object ${objectName} {`,
		`    fun ${functionName}(): ASTDProjectileVfxPreset = ASTDProjectileVfxPreset(`,
		`        id = ${quoteKotlin(preset.id)},`,
		`        components = ${emitComponents(preset)},`,
		`        samplingPolicy = ${emitSamplePolicy(preset.samplingPolicy)},`,
		`        fadePolicy = ASTDProjectileVfxFadePolicy(`,
		`            fadeInSeconds = ${formatFloat(preset.lifecycle.fadeInSeconds)},`,
		`            fadeOutSeconds = ${formatFloat(preset.lifecycle.fadeOutSeconds)},`,
		`            hitFadeOutSeconds = ${formatFloat(preset.lifecycle.fadeOutSeconds)},`,
		`            expireFadeOutSeconds = ${formatFloat(preset.lifecycle.fadeOutSeconds)},`,
		`        ),`,
		`        lifecycle = ${emitLifecycle(preset.lifecycle)},`,
		`    )`,
		`}`,
		'',
	].join('\n');
}

function emitComponents(preset: GameProjectileVfxPreset): string {
	const components: string[] = [];
	for (const entity of preset.trailEntities) {
		components.push(emitTrailComponent(entity));
	}
	const trailId = preset.trailEntities[0]?.id;
	if (trailId) {
		if (preset.mistLayers.length > 0) components.push(emitMistComponent(trailId, preset.mistLayers));
		if (preset.glowLayers.length > 0) components.push(emitGlowComponent(trailId, preset.glowLayers));
		components.push(emitBodyComponent(trailId));
		if (preset.sideWispLayers.length > 0) components.push(emitSideWispComponent(trailId, preset.sideWispLayers));
		if (preset.headLayers.length > 0) components.push(emitHeadComponent(trailId, preset.headLayers));
		const ribbons = preset.ribbonDecorations.length > 0 ? preset.ribbonDecorations : preset.trailEntities[0]?.ribbonDecorations ?? [];
		if (ribbons.length > 0) components.push(emitRibbonComponent(trailId, ribbons));
	}
	return emitList(components, (component) => component);
}

function emitTrailComponent(entity: GameTrailEntityConfig): string {
	return [
		'ASTDProjectileVfxComponentSpec.Trail(',
		`            id = ${quoteKotlin(entity.id)},`,
		`            layer = ${emitTrailLayer(entity)},`,
		`            orientationMode = ${emitOrientationMode(entity.orientationMode)},`,
		`            anchorMode = ${emitAnchorMode(entity.anchorMode)},`,
		'        )',
	].join('\n');
}

function emitBodyComponent(trailId: string): string {
	return [
		'ASTDProjectileVfxComponentSpec.Body(',
		`            id = ${quoteKotlin(`${trailId}_body`)},`,
		`            trailId = ${quoteKotlin(trailId)},`,
		'        )',
	].join('\n');
}

function emitRibbonComponent(trailId: string, ribbons: GameTrailRibbonDecorationConfig[]): string {
	return [
		'ASTDProjectileVfxComponentSpec.Ribbon(',
		`            id = ${quoteKotlin(`${trailId}_ribbon`)},`,
		`            trailId = ${quoteKotlin(trailId)},`,
		`            ribbons = ${emitRibbonDecorations(ribbons)},`,
		'        )',
	].join('\n');
}

function emitHeadComponent(trailId: string, layers: ProjectileVfxHeadLayerConfig[]): string {
	return [
		'ASTDProjectileVfxComponentSpec.Head(',
		`            id = ${quoteKotlin(`${trailId}_head`)},`,
		`            trailId = ${quoteKotlin(trailId)},`,
		`            layers = ${emitHeadLayers(layers)},`,
		'        )',
	].join('\n');
}

function emitGlowComponent(trailId: string, layers: ProjectileVfxGlowLayerConfig[]): string {
	return [
		'ASTDProjectileVfxComponentSpec.Glow(',
		`            id = ${quoteKotlin(`${trailId}_glow`)},`,
		`            trailId = ${quoteKotlin(trailId)},`,
		`            layers = ${emitGlowLayers(layers)},`,
		'        )',
	].join('\n');
}

function emitMistComponent(trailId: string, layers: ProjectileVfxMistLayerConfig[]): string {
	return [
		'ASTDProjectileVfxComponentSpec.Mist(',
		`            id = ${quoteKotlin(`${trailId}_mist`)},`,
		`            trailId = ${quoteKotlin(trailId)},`,
		`            layers = ${emitMistLayers(layers)},`,
		'        )',
	].join('\n');
}

function emitSideWispComponent(trailId: string, layers: ProjectileVfxSideWispLayerConfig[]): string {
	return [
		'ASTDProjectileVfxComponentSpec.SideWisp(',
		`            id = ${quoteKotlin(`${trailId}_side_wisp`)},`,
		`            trailId = ${quoteKotlin(trailId)},`,
		`            layers = ${emitSideWispLayers(layers)},`,
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
		`            layoutReferenceWidth = ${formatFloat(lifecycle.layoutReferenceWidth)},`,
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
