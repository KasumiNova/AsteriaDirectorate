import type { BeamPoint, BeamPreset } from '../model/beamPreset';

export interface BeamSample {
  x: number;
  y: number;
  progress: number;
  tangentX: number;
  tangentY: number;
  normalX: number;
  normalY: number;
}

export interface BeamSamplingOptions {
  sampleCount?: number;
}

export function sampleBeamPath(preset: BeamPreset, options: BeamSamplingOptions = {}): BeamSample[] {
  const sampleCount = Math.max(2, Math.round(options.sampleCount ?? preset.quality));
  const points = normalizeControlPoints(preset);
  const samples: BeamSample[] = [];
  let previousNormal: Pick<BeamSample, 'normalX' | 'normalY'> | undefined;

  for (let index = 0; index < sampleCount; index += 1) {
    const progress = sampleCount === 1 ? 0 : index / (sampleCount - 1);
    const point = pointAt(points, progress);
    const tangent = normalize(derivativeAt(points, progress));
    let normal = { x: -tangent.y, y: tangent.x };

    if (previousNormal && normal.x * previousNormal.normalX + normal.y * previousNormal.normalY < 0) {
      normal = { x: -normal.x, y: -normal.y };
    }

    samples.push({
      x: point.x,
      y: point.y,
      progress,
      tangentX: tangent.x,
      tangentY: tangent.y,
      normalX: normal.x,
      normalY: normal.y,
    });
    previousNormal = { normalX: normal.x, normalY: normal.y };
  }

  return samples;
}

function normalizeControlPoints(preset: BeamPreset): BeamPoint[] {
  if (preset.mode === 'straight') {
    const start = preset.controlPoints[0] ?? { x: 0, y: 0 };
    const end = last(preset.controlPoints) ?? { x: start.x + 1, y: start.y };
    return [start, end];
  }

  if (preset.controlPoints.length >= 4) {
    return preset.controlPoints.slice(0, 4);
  }
  if (preset.controlPoints.length >= 3) {
    return preset.controlPoints.slice(0, 3);
  }

  const start = preset.controlPoints[0] ?? { x: 0, y: 0 };
  const end = last(preset.controlPoints) ?? { x: start.x + 1, y: start.y };
  return [start, { x: (start.x + end.x) * 0.5, y: start.y - 80 }, end];
}

function pointAt(points: BeamPoint[], t: number): BeamPoint {
  if (points.length === 2) {
    return lerpPoint(points[0], points[1], t);
  }
  if (points.length === 3) {
    const a = lerpPoint(points[0], points[1], t);
    const b = lerpPoint(points[1], points[2], t);
    return lerpPoint(a, b, t);
  }

  const a = lerpPoint(points[0], points[1], t);
  const b = lerpPoint(points[1], points[2], t);
  const c = lerpPoint(points[2], points[3], t);
  return lerpPoint(lerpPoint(a, b, t), lerpPoint(b, c, t), t);
}

function derivativeAt(points: BeamPoint[], t: number): BeamPoint {
  if (points.length === 2) {
    return { x: points[1].x - points[0].x, y: points[1].y - points[0].y };
  }
  if (points.length === 3) {
    return {
      x: 2 * (1 - t) * (points[1].x - points[0].x) + 2 * t * (points[2].x - points[1].x),
      y: 2 * (1 - t) * (points[1].y - points[0].y) + 2 * t * (points[2].y - points[1].y),
    };
  }

  return {
    x:
      3 * (1 - t) ** 2 * (points[1].x - points[0].x) +
      6 * (1 - t) * t * (points[2].x - points[1].x) +
      3 * t ** 2 * (points[3].x - points[2].x),
    y:
      3 * (1 - t) ** 2 * (points[1].y - points[0].y) +
      6 * (1 - t) * t * (points[2].y - points[1].y) +
      3 * t ** 2 * (points[3].y - points[2].y),
  };
}

function lerpPoint(a: BeamPoint, b: BeamPoint, t: number): BeamPoint {
  return { x: lerp(a.x, b.x, t), y: lerp(a.y, b.y, t) };
}

export function lerp(a: number, b: number, t: number): number {
  return a + (b - a) * t;
}

function normalize(point: BeamPoint): BeamPoint {
  const length = Math.hypot(point.x, point.y);
  if (!Number.isFinite(length) || length < 0.0001) {
    return { x: 1, y: 0 };
  }
  return { x: point.x / length, y: point.y / length };
}

function last<T>(items: T[]): T | undefined {
  return items.length > 0 ? items[items.length - 1] : undefined;
}
