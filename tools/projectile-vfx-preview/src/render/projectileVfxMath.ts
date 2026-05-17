export type Vec2 = [number, number];

export function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}

export function smoothstep(edge0: number, edge1: number, x: number): number {
  const t = clamp((x - edge0) / Math.max(edge1 - edge0, 0.0001), 0, 1);
  return t * t * (3 - 2 * t);
}

export function hermite01(t: number, m0: number, m1: number): number {
  const u = clamp(t, 0, 1);
  const u2 = u * u;
  const u3 = u2 * u;
  return (u3 - 2 * u2 + u) * m0 + (-2 * u3 + 3 * u2) + (u3 - u2) * m1;
}

export function shaderNoise(x: number, y: number): number {
  return fract(Math.sin(x * 127.1 + y * 311.7) * 43758.5453123);
}

export function layeredNoise(x: number, y: number): number {
  return shaderNoise(x, y) * 0.52 + shaderNoise(x * 2.13 + 17.4, y * 2.31 - 9.2) * 0.32 + shaderNoise(x * 4.07 - 3.8, y * 3.63 + 21.6) * 0.16;
}

export function sampleHistoryAt(history: Vec2[], targetDist: number, histPixelsPerEntry: number): Vec2 {
  if (history.length === 0) return [0, 0];
  if (targetDist <= 0 || history.length === 1) return history[0];
  const rawIdx = targetDist / Math.max(histPixelsPerEntry, 0.1);
  const idx0 = Math.floor(rawIdx);
  const idx1 = idx0 + 1;
  if (idx0 >= history.length - 1) return history[history.length - 1];
  const frac = rawIdx - idx0;
  const p0 = history[idx0];
  const p1 = history[idx1];
  return [lerp(p0[0], p1[0], frac), lerp(p0[1], p1[1], frac)];
}

export function ribbonWave(type: 'sine' | 'noise' | 'zigzag', worldX: number, timeSeconds: number, frequency: number, speed: number, amplitude: number, noiseScale: number, syncCode: number, softening: number): number {
  const worldTimePhase = timeSeconds * speed * 0.18 + syncCode * 0.05;
  if (type === 'noise') {
    const noiseVal = layeredNoise(worldX * noiseScale * 0.005, worldTimePhase);
    return (smoothstep(0.12, 0.88, noiseVal) - 0.5) * 2 * amplitude * softening;
  }
  if (type === 'zigzag') {
    const phase = worldX * frequency * 0.01 + timeSeconds * speed;
    const raw = 1 - 4 * Math.abs(fract(phase + 0.25) - 0.5);
    return (raw >= 0 ? smoothstep(0, 1, raw) : -smoothstep(0, 1, -raw)) * amplitude * softening;
  }
  const phase = worldX * frequency * 0.01 + timeSeconds * speed;
  return Math.sin(phase * Math.PI * 2 + syncCode * 0.05) * amplitude * softening;
}

export function dissolve(elapsed: number, duration: number, dissolveStartRatio: number): number {
  const start = duration * dissolveStartRatio;
  return clamp((elapsed - start) / Math.max(duration - start, 0.0001), 0, 1);
}

export function beamAlpha(dissolveValue: number): number {
  return clamp((1 - dissolveValue) * (1 - dissolveValue) * (1 - dissolveValue * 0.48), 0, 1);
}

export function visibleLength(baseLength: number, dissolveValue: number): number {
  return baseLength * (1 + (0.08 - 1) * dissolveValue);
}

function lerp(start: number, end: number, t: number): number {
  return start + (end - start) * t;
}

function fract(value: number): number {
  return value - Math.floor(value);
}
