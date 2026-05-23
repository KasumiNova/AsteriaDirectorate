import type { BeamLayer, BeamPoint, BeamPreset } from '../model/beamPreset';

export function exportKotlinBeamDraftPreset(preset: BeamPreset): string {
  return [
    '// Draft authoring contract only. This is not wired to runtime until a Beam component exists.',
    'ASTDBeamVfxDraftPreset(',
    `    id = "${escapeKotlin(preset.id)}",`,
    `    mode = ASTDBeamVfxDraftMode.${preset.mode === 'curved' ? 'Curved' : 'Straight'},`,
    `    controlPoints = listOf(${preset.controlPoints.map(exportPoint).join(', ')}),`,
    '    layers = listOf(',
    preset.layers.map(exportLayer).join(',\n'),
    '    ),',
    ')',
  ].join('\n');
}

function exportPoint(point: BeamPoint): string {
  return `ASTDBeamVfxDraftPoint(${floatLiteral(point.x)}, ${floatLiteral(point.y)})`;
}

function exportLayer(layer: BeamLayer): string {
  return [
    '        ASTDBeamVfxDraftLayer(',
    `            id = "${escapeKotlin(layer.id)}",`,
    `            enabled = ${layer.enabled},`,
    `            widthStart = ${floatLiteral(layer.widthStart)},`,
    `            widthEnd = ${floatLiteral(layer.widthEnd)},`,
    `            colorStart = "${escapeKotlin(layer.colorStart)}",`,
    `            colorEnd = "${escapeKotlin(layer.colorEnd)}",`,
    `            emissiveStart = ${floatLiteral(layer.emissiveStart)},`,
    `            emissiveEnd = ${floatLiteral(layer.emissiveEnd)},`,
    `            textureSpeed = ${floatLiteral(layer.textureSpeed)},`,
    `            noiseStrength = ${floatLiteral(layer.noiseStrength)},`,
    `            noiseScale = ${floatLiteral(layer.noiseScale)},`,
    `            bloomStrength = ${floatLiteral(layer.bloomStrength)},`,
    `            blendMode = ASTDBeamVfxDraftBlendMode.${capitalize(layer.blendMode)},`,
    '        )',
  ].join('\n');
}

function floatLiteral(value: number): string {
  const rounded = Number.isInteger(value) ? value.toFixed(1) : Number(value.toFixed(4)).toString();
  return `${rounded}f`;
}

function escapeKotlin(value: string): string {
  return value.replace(/\\/g, '\\\\').replace(/"/g, '\\"');
}

function capitalize(value: string): string {
  return `${value.charAt(0).toUpperCase()}${value.slice(1)}`;
}
