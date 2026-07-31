export function beamNoiseAt(layerId: string, progress: number, timeSeconds: number, scale: number): number {
  const x = progress * Math.max(0.001, scale) + hashString(layerId) * 0.013;
  const t = timeSeconds * 0.65;
  const left = Math.floor(x + t);
  const local = x + t - left;
  const a = hash01(layerId, left);
  const b = hash01(layerId, left + 1);
  return lerp(a, b, smoothstep(local));
}

function hashString(value: string): number {
  let hash = 2166136261;
  for (let index = 0; index < value.length; index += 1) {
    hash ^= value.charCodeAt(index);
    hash = Math.imul(hash, 16777619);
  }
  return hash >>> 0;
}

function hash01(layerId: string, index: number): number {
  const seed = hashString(`${layerId}:${index}`);
  const mixed = Math.imul(seed ^ (seed >>> 16), 2246822519);
  return ((mixed ^ (mixed >>> 13)) >>> 0) / 4294967295;
}

function smoothstep(t: number): number {
  return t * t * (3 - 2 * t);
}

function lerp(a: number, b: number, t: number): number {
  return a + (b - a) * t;
}
