import { ProjectileVfxGlowLayerConfig, ProjectileVfxHeadLayerConfig, ProjectileVfxSideWispLayerConfig, Rgba, TrailEntityConfig, Vec2 } from '../model/preset';
import { beamAlpha, dissolve, visibleLength } from './projectileVfxMath';

export interface ProjectileVfxFlightLayout {
  dissolve: number;
  beamAlpha: number;
  visibleLength: number;
}

export interface ProjectileVfxHeadVertexLayout {
  rearTop: Vec2;
  shoulderTop: Vec2;
  curveTop: Vec2;
  tip: Vec2;
  curveBottom: Vec2;
  shoulderBottom: Vec2;
  rearBottom: Vec2;
}

export interface ProjectileVfxHeadColorLayout {
  start: Rgba;
  mid: Rgba;
  end: Rgba;
  emissive: Rgba;
}

export function projectileVfxWidthBase(trail: Pick<TrailEntityConfig, 'startWidth'>): number {
  return Math.max(trail.startWidth * 0.075, 3.5);
}

export function projectileVfxFlightLayout(baseLength: number, elapsed: number, durationSeconds: number, dissolveStartRatio: number): ProjectileVfxFlightLayout {
  const dissolveValue = dissolve(elapsed, durationSeconds, dissolveStartRatio);
  return {
    dissolve: dissolveValue,
    beamAlpha: beamAlpha(dissolveValue),
    visibleLength: visibleLength(baseLength, dissolveValue),
  };
}

export function projectileVfxTrailLocalNodes(visibleLengthValue: number, yOffset = 0): Vec2[] {
  return [[-Math.max(0, visibleLengthValue), yOffset], [0, yOffset]];
}

export function projectileVfxGlowLocalNodes(visibleLengthValue: number, glow: Pick<ProjectileVfxGlowLayerConfig, 'yOffset'>): Vec2[] {
  return projectileVfxTrailLocalNodes(visibleLengthValue, glow.yOffset);
}

export function projectileVfxGlowLineWidth(widthBase: number, glow: Pick<ProjectileVfxGlowLayerConfig, 'widthScale'>): number {
  return widthBase * glow.widthScale;
}

const PROJECTILE_VFX_HEAD_AUTHORED_WIDTH_BASE = 6;

export function projectileVfxHeadTrailScale(widthBase: number): number {
  return Math.max(0.01, widthBase / PROJECTILE_VFX_HEAD_AUTHORED_WIDTH_BASE);
}

export function projectileVfxHeadVertices(layer: Pick<ProjectileVfxHeadLayerConfig, 'length' | 'width' | 'shoulderRatio' | 'rearRatio'>, visible: number, headSizeScale = 1, widthBase = PROJECTILE_VFX_HEAD_AUTHORED_WIDTH_BASE): ProjectileVfxHeadVertexLayout {
  const scale = headSizeScale * projectileVfxHeadTrailScale(widthBase);
  const length = Math.max(1, layer.length) * visible * scale;
  const width = Math.max(1, layer.width) * visible * scale;
  const shoulderX = -length * layer.shoulderRatio;
  const rearX = -length * layer.rearRatio;
  return {
    rearTop: [rearX, -width * 0.2],
    shoulderTop: [shoulderX, -width * 0.52],
    curveTop: [-length * 0.12, -width * 0.3],
    tip: [0, 0],
    curveBottom: [-length * 0.12, width * 0.3],
    shoulderBottom: [shoulderX, width * 0.52],
    rearBottom: [rearX, width * 0.2],
  };
}

export function projectileVfxHeadColors(
  trail: Pick<TrailEntityConfig, 'startColor' | 'endColor' | 'startEmissive' | 'endEmissive'>,
  layer: Pick<ProjectileVfxHeadLayerConfig, 'shellColorStart' | 'shellColorMid' | 'shellColorEnd'>,
): ProjectileVfxHeadColorLayout {
  const edge = mixRgba(trail.startColor, trail.startEmissive, 0.48);
  const hot = mixRgba(trail.startEmissive, [1, 1, 1, 1], 0.72);
  return {
    start: multiplyRgbAlpha(trail.endColor, layer.shellColorStart),
    mid: multiplyRgbAlpha(edge, layer.shellColorMid),
    end: multiplyRgbAlpha(hot, layer.shellColorEnd),
    emissive: multiplyRgbAlpha(hot, layer.shellColorEnd),
  };
}

export function projectileVfxSideWispLocalPaths(layer: Pick<ProjectileVfxSideWispLayerConfig, 'offsets' | 'lengthStartRatio' | 'lengthEndRatio'>, visibleLengthValue: number, widthBase: number): Vec2[][] {
  return layer.offsets.map((offsetScale) => {
    const offset = widthBase * offsetScale;
    return [
      [-visibleLengthValue * layer.lengthStartRatio, offset],
      [-visibleLengthValue * layer.lengthEndRatio, offset * 0.66],
      [-widthBase * 2.6, offset * 0.18],
    ];
  });
}

function multiplyRgbAlpha(base: Rgba, tint: Rgba): Rgba {
  return [base[0] * tint[0], base[1] * tint[1], base[2] * tint[2], base[3] * tint[3]];
}

function mixRgba(start: Rgba, end: Rgba, t: number): Rgba {
  const ratio = Math.max(0, Math.min(1, t));
  return [
    start[0] + (end[0] - start[0]) * ratio,
    start[1] + (end[1] - start[1]) * ratio,
    start[2] + (end[2] - start[2]) * ratio,
    start[3] + (end[3] - start[3]) * ratio,
  ];
}
