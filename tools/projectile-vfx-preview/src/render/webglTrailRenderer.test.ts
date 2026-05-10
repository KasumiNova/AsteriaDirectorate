import { describe, expect, it, vi } from 'vitest';
import { createDefaultPreset } from '../model/preset';
import { buildTrailMesh } from './trailGeometry';
import { TRAIL_FRAGMENT_SHADER_SOURCE, TRAIL_VERTEX_SHADER_SOURCE, createWebGLTrailRenderer } from './webglTrailRenderer';

describe('shader sources', () => {
  it('contain TrailEntity uniforms and attributes', () => {
    expect(TRAIL_VERTEX_SHADER_SOURCE).toContain('a_position');
    expect(TRAIL_VERTEX_SHADER_SOURCE).toContain('a_uv');
    expect(TRAIL_VERTEX_SHADER_SOURCE).toContain('a_color');
    expect(TRAIL_VERTEX_SHADER_SOURCE).toContain('a_emissive');
    expect(TRAIL_FRAGMENT_SHADER_SOURCE).toContain('u_time');
    expect(TRAIL_FRAGMENT_SHADER_SOURCE).toContain('u_jitterPower');
    expect(TRAIL_FRAGMENT_SHADER_SOURCE).toContain('u_flickMixValue');
    expect(TRAIL_FRAGMENT_SHADER_SOURCE).toContain('v_uv');
    expect(TRAIL_FRAGMENT_SHADER_SOURCE).toContain('noise(vec2 p)');
    expect(TRAIL_FRAGMENT_SHADER_SOURCE).toContain('beamNoise');
  });
});

describe('createWebGLTrailRenderer', () => {
  it('returns null when WebGL is unavailable', () => {
    const canvas = document.createElement('canvas');
    vi.spyOn(canvas, 'getContext').mockReturnValue(null);

    expect(createWebGLTrailRenderer(canvas)).toBeNull();
  });

  it('accepts a mesh and updates draw stats', () => {
    const canvas = document.createElement('canvas');
    const renderer = createWebGLTrailRenderer(canvas, createMockGl());
    const preset = createDefaultPreset();
    const mesh = buildTrailMesh(preset.trailEntities[0], 0);

    renderer?.drawTrailMesh(mesh, preset.trailEntities[0], 0);

    expect(renderer?.getStats()).toEqual({ drawCalls: 1, vertexCount: mesh.vertices.length });
  });
});

describe('preview overlay ribbon rendering', () => {
  it('uses normal blending for ribbon decorations', async () => {
    const module = await import('./previewOverlayRenderer');
    const canvas = document.createElement('canvas');
    const context = createMockContext2D();
    vi.spyOn(canvas, 'getContext').mockReturnValue(context as unknown as CanvasRenderingContext2D);

    const renderer = module.createPreviewOverlayRenderer(canvas);
    renderer?.resize(960, 540);
    expect(() => renderer?.render(createDefaultPreset(), 0.4)).not.toThrow();

    expect(context.quadraticCurveTo).toHaveBeenCalled();
    expect(context.globalCompositeOperation).toBe('source-over');
  });
});

function createMockGl(): WebGLRenderingContext {
  const gl = {
    VERTEX_SHADER: 0x8b31,
    FRAGMENT_SHADER: 0x8b30,
    COMPILE_STATUS: 0x8b81,
    LINK_STATUS: 0x8b82,
    ARRAY_BUFFER: 0x8892,
    STATIC_DRAW: 0x88e4,
    FLOAT: 0x1406,
    TRIANGLES: 0x0004,
    BLEND: 0x0be2,
    SRC_ALPHA: 0x0302,
    ONE_MINUS_SRC_ALPHA: 0x0303,
    ONE: 1,
    COLOR_BUFFER_BIT: 0x4000,
    createShader: vi.fn(() => ({})),
    shaderSource: vi.fn(),
    compileShader: vi.fn(),
    getShaderParameter: vi.fn(() => true),
    getShaderInfoLog: vi.fn(() => ''),
    createProgram: vi.fn(() => ({})),
    attachShader: vi.fn(),
    linkProgram: vi.fn(),
    getProgramParameter: vi.fn(() => true),
    getProgramInfoLog: vi.fn(() => ''),
    createBuffer: vi.fn(() => ({})),
    useProgram: vi.fn(),
    bindBuffer: vi.fn(),
    bufferData: vi.fn(),
    getAttribLocation: vi.fn(() => 0),
    enableVertexAttribArray: vi.fn(),
    vertexAttribPointer: vi.fn(),
    getUniformLocation: vi.fn(() => ({})),
    uniform1f: vi.fn(),
    viewport: vi.fn(),
    clearColor: vi.fn(),
    clear: vi.fn(),
    enable: vi.fn(),
    blendFunc: vi.fn(),
    drawArrays: vi.fn(),
  };
  return gl as unknown as WebGLRenderingContext;
}

function createMockContext2D(): Partial<CanvasRenderingContext2D> {
  const ctx: Partial<CanvasRenderingContext2D> = {
    clearRect: vi.fn(),
    createRadialGradient: vi.fn(() => ({ addColorStop: vi.fn() } as unknown as CanvasGradient)),
    createLinearGradient: vi.fn(() => ({ addColorStop: vi.fn() } as unknown as CanvasGradient)),
    fillRect: vi.fn(),
    beginPath: vi.fn(),
    moveTo: vi.fn(),
    lineTo: vi.fn(),
    stroke: vi.fn(),
    fill: vi.fn(),
    arc: vi.fn(),
    clip: vi.fn(),
    save: vi.fn(),
    restore: vi.fn(),
    translate: vi.fn(),
    rotate: vi.fn(),
    scale: vi.fn(),
    setLineDash: vi.fn(),
    closePath: vi.fn(),
    fillStyle: '',
    strokeStyle: '',
    lineWidth: 1,
    lineCap: 'round',
    lineJoin: 'round',
    shadowBlur: 0,
    shadowColor: '',
    filter: 'none',
    globalCompositeOperation: 'source-over',
    quadraticCurveTo: vi.fn(),
  };
  return ctx;
}
