import { describe, expect, it } from 'vitest';
import { createDefaultPreset } from '../model/preset';
import { buildTrailMesh, computeTrailDistances } from './trailGeometry';

describe('computeTrailDistances', () => {
  it('computes cumulative node distance', () => {
    const distances = computeTrailDistances([
      { position: [0, 0] },
      { position: [3, 4] },
      { position: [6, 8] },
    ]);

    expect(distances).toEqual([0, 5, 10]);
  });
});

describe('buildTrailMesh', () => {
  it('creates one quad from two nodes', () => {
    const trail = createDefaultPreset().trailEntities[0];
    trail.nodes = [{ position: [0, 0] }, { position: [10, 0] }];

    const mesh = buildTrailMesh(trail, 0);

    expect(mesh.vertices).toHaveLength(6);
    expect(mesh.triangleCount).toBe(2);
  });

  it('creates two quads from three nodes', () => {
    const trail = createDefaultPreset().trailEntities[0];
    trail.nodes = [{ position: [0, 0] }, { position: [10, 0] }, { position: [20, 0] }];

    const mesh = buildTrailMesh(trail, 0);

    expect(mesh.vertices).toHaveLength(12);
    expect(mesh.triangleCount).toBe(4);
  });

  it('stores position, UV, color, emissive and alpha per vertex', () => {
    const trail = createDefaultPreset().trailEntities[0];
    trail.nodes = [{ position: [0, 0] }, { position: [10, 0] }];

    const mesh = buildTrailMesh(trail, 0);
    const vertex = mesh.vertices[0];

    expect(vertex.position).toHaveLength(2);
    expect(vertex.uv).toHaveLength(2);
    expect(vertex.color).toHaveLength(4);
    expect(vertex.emissive).toHaveLength(4);
    expect(vertex.alpha).toEqual(expect.any(Number));
  });

  it('uses cumulative distance for V coordinates', () => {
    const trail = createDefaultPreset().trailEntities[0];
    trail.nodes = [{ position: [0, 0] }, { position: [50, 0] }, { position: [100, 0] }];
    trail.texturePixels = 50;

    const mesh = buildTrailMesh(trail, 0);

    expect(mesh.vertices.some((vertex) => vertex.uv[1] === 2)).toBe(true);
  });

  it('uses start and end widths for lateral offsets', () => {
    const trail = createDefaultPreset().trailEntities[0];
    trail.nodes = [{ position: [0, 0] }, { position: [10, 0] }];
    trail.startWidth = 20;
    trail.endWidth = 4;

    const mesh = buildTrailMesh(trail, 0);

    expect(mesh.vertices[0].position[1]).toBeCloseTo(10);
    expect(mesh.vertices[2].position[1]).toBeCloseTo(2);
  });

  it('applies fill alpha taper', () => {
    const trail = createDefaultPreset().trailEntities[0];
    trail.nodes = [{ position: [0, 0] }, { position: [10, 0] }];
    trail.fillStartAlpha = 0.5;
    trail.fillEndAlpha = 0.25;

    const mesh = buildTrailMesh(trail, 0);

    expect(mesh.vertices[0].alpha).toBeCloseTo(0.5);
    expect(mesh.vertices[2].alpha).toBeCloseTo(0.25);
  });
});
