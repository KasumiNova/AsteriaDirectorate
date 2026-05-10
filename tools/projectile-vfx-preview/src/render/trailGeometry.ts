import { Rgba, TrailEntityConfig, TrailNode, Vec2 } from '../model/preset';

export interface TrailVertex {
  position: Vec2;
  uv: Vec2;
  color: Rgba;
  emissive: Rgba;
  alpha: number;
  jitterPower: number;
}

export interface TrailMesh {
  vertices: TrailVertex[];
  triangleCount: number;
  jitterPower: number;
  blendMode: TrailEntityConfig['blendMode'];
}

export function computeTrailDistances(nodes: TrailNode[]): number[] {
  const distances = [0];
  for (let i = 1; i < nodes.length; i += 1) {
    const previous = nodes[i - 1].position;
    const current = nodes[i].position;
    distances.push(distances[i - 1] + Math.hypot(current[0] - previous[0], current[1] - previous[1]));
  }
  return distances;
}

export function buildTrailMesh(config: TrailEntityConfig, timeSeconds: number): TrailMesh {
  if (config.nodes.length < 2) {
    return { vertices: [], triangleCount: 0, jitterPower: config.jitterPower, blendMode: config.blendMode };
  }

  const distances = computeTrailDistances(config.nodes);
  const totalDistance = Math.max(distances[distances.length - 1], 0.0001);
  const pairs = config.nodes.map((node, index) => {
    const t = distances[index] / totalDistance;
    const tangent = computeTangent(config.nodes, index);
    const normal: Vec2 = [-tangent[1], tangent[0]];
    const width = lerp(config.startWidth, config.endWidth, t);
    const halfWidth = width * 0.5;
    const alpha = computeTaperAlpha(t, config);
    const uvV = distances[index] / Math.max(config.texturePixels, 0.0001) + config.uvOffset + timeSeconds * config.textureSpeed;
    const center = node.position;

    return {
      left: createVertex(center, normal, halfWidth, [0, uvV], t, alpha, config),
      right: createVertex(center, normal, -halfWidth, [1, uvV], t, alpha, config),
    };
  });

  const vertices: TrailVertex[] = [];
  for (let i = 0; i < pairs.length - 1; i += 1) {
    const a = pairs[i];
    const b = pairs[i + 1];
    vertices.push(a.left, a.right, b.left, b.left, a.right, b.right);
  }

  return {
    vertices,
    triangleCount: vertices.length / 3,
    jitterPower: config.jitterPower,
    blendMode: config.blendMode,
  };
}

function computeTangent(nodes: TrailNode[], index: number): Vec2 {
  const previous = nodes[Math.max(0, index - 1)].position;
  const next = nodes[Math.min(nodes.length - 1, index + 1)].position;
  const dx = next[0] - previous[0];
  const dy = next[1] - previous[1];
  const length = Math.hypot(dx, dy) || 1;
  return [dx / length, dy / length];
}

function createVertex(center: Vec2, normal: Vec2, offset: number, uv: Vec2, t: number, alpha: number, config: TrailEntityConfig): TrailVertex {
  return {
    position: [center[0] + normal[0] * offset, center[1] + normal[1] * offset],
    uv,
    color: interpolateRgba(config.startColor, config.endColor, t),
    emissive: interpolateRgba(config.startEmissive, config.endEmissive, t),
    alpha,
    jitterPower: config.jitterPower,
  };
}

function computeTaperAlpha(t: number, config: TrailEntityConfig): number {
  const shapedT = shapeTaperCoordinate(t, config.fillStartFactor, config.fillEndFactor);
  return lerp(config.fillStartAlpha, config.fillEndAlpha, shapedT);
}

function shapeTaperCoordinate(t: number, startFactor: number, endFactor: number): number {
  const startWeight = startFactor > 0 ? clamp(t / startFactor, 0, 1) : 1;
  const endWeight = endFactor > 0 ? clamp((1 - t) / endFactor, 0, 1) : 1;
  const balance = startWeight / Math.max(startWeight + endWeight, 0.0001);
  if (t === 0 || t === 1) {
    return t;
  }
  return clamp((t + balance) * 0.5, 0, 1);
}

function interpolateRgba(start: Rgba, end: Rgba, t: number): Rgba {
  return [lerp(start[0], end[0], t), lerp(start[1], end[1], t), lerp(start[2], end[2], t), lerp(start[3], end[3], t)];
}

function lerp(start: number, end: number, t: number): number {
  return start + (end - start) * t;
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}
