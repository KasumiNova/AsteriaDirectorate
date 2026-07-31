export type BeamMode = 'straight' | 'curved';
export type BeamBlendMode = 'additive' | 'alpha' | 'screen';

export interface BeamPoint {
  x: number;
  y: number;
}

export interface BeamLayer {
  id: string;
  name: string;
  enabled: boolean;
  widthStart: number;
  widthEnd: number;
  colorStart: string;
  colorEnd: string;
  emissiveStart: number;
  emissiveEnd: number;
  textureSpeed: number;
  noiseStrength: number;
  noiseScale: number;
  bloomStrength: number;
  blendMode: BeamBlendMode;
}

export interface BeamPreset {
  id: string;
  name: string;
  mode: BeamMode;
  controlPoints: BeamPoint[];
  quality: number;
  layers: BeamLayer[];
}

export function createDefaultBeamPreset(): BeamPreset {
  return {
    id: 'astd_beam_draft',
    name: 'ASTD Beam Draft',
    mode: 'straight',
    controlPoints: [
      { x: 80, y: 240 },
      { x: 820, y: 240 },
    ],
    quality: 56,
    layers: [
      {
        id: 'halo',
        name: 'Halo',
        enabled: true,
        widthStart: 78,
        widthEnd: 42,
        colorStart: '#58d8ff',
        colorEnd: '#7b68ff',
        emissiveStart: 0.55,
        emissiveEnd: 0.45,
        textureSpeed: 0.16,
        noiseStrength: 0.18,
        noiseScale: 5.5,
        bloomStrength: 0.72,
        blendMode: 'screen',
      },
      {
        id: 'core',
        name: 'Core',
        enabled: true,
        widthStart: 24,
        widthEnd: 12,
        colorStart: '#f8fbff',
        colorEnd: '#9ef4ff',
        emissiveStart: 1,
        emissiveEnd: 0.86,
        textureSpeed: 0.42,
        noiseStrength: 0.08,
        noiseScale: 11,
        bloomStrength: 0.38,
        blendMode: 'additive',
      },
    ],
  };
}

export function controlPointsForMode(preset: BeamPreset, mode: BeamMode): BeamPoint[] {
  if (mode === 'straight') {
    const start = preset.controlPoints[0] ?? { x: 80, y: 240 };
    const end = last(preset.controlPoints) ?? { x: 820, y: 240 };
    return [start, end];
  }

  const start = preset.controlPoints[0] ?? { x: 80, y: 240 };
  const end = last(preset.controlPoints) ?? { x: 820, y: 240 };
  const mid = preset.controlPoints.length >= 3
    ? preset.controlPoints[1]
    : { x: (start.x + end.x) * 0.5, y: Math.min(start.y, end.y) - 120 };
  return [start, mid, end];
}

export function cloneLayer(layer: BeamLayer, suffix: string): BeamLayer {
  return {
    ...layer,
    id: `${layer.id}_${suffix}`,
    name: `${layer.name} ${suffix}`,
  };
}

function last<T>(items: T[]): T | undefined {
  return items.length > 0 ? items[items.length - 1] : undefined;
}
