import { render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createDefaultPreset } from '../model/preset';
import { WebGLTrailRenderer } from '../render/webglTrailRenderer';
import { PreviewCanvas } from './PreviewCanvas';

describe('PreviewCanvas', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('renders an accessible canvas', () => {
    render(<PreviewCanvas preset={createDefaultPreset()} timeSeconds={0} />);

    expect(screen.getByLabelText('Projectile VFX preview canvas')).toBeInTheDocument();
  });

  it('displays fallback text when WebGL is unavailable', () => {
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(null);

    render(<PreviewCanvas preset={createDefaultPreset()} timeSeconds={0} />);

    expect(screen.getByText(/WebGL unavailable/i)).toBeInTheDocument();
  });

  it('does not draw unprojected TrailEntity meshes on the base WebGL canvas', () => {
    const drawTrailMesh = vi.spyOn(WebGLTrailRenderer.prototype, 'drawTrailMesh');
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockImplementation((contextId: string) => {
      if (contextId === 'webgl') {
        return createMockGl() as unknown as RenderingContext;
      }
      if (contextId === '2d') {
        return createMockContext2D() as unknown as RenderingContext;
      }
      return null;
    });

    render(<PreviewCanvas preset={createDefaultPreset()} timeSeconds={0.42} />);

    expect(drawTrailMesh).not.toHaveBeenCalled();
  });
});

function createMockGl(): Partial<WebGLRenderingContext> {
  return {
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
    createShader: vi.fn(() => ({} as WebGLShader)),
    shaderSource: vi.fn(),
    compileShader: vi.fn(),
    getShaderParameter: vi.fn(() => true),
    getShaderInfoLog: vi.fn(() => ''),
    createProgram: vi.fn(() => ({} as WebGLProgram)),
    attachShader: vi.fn(),
    linkProgram: vi.fn(),
    getProgramParameter: vi.fn(() => true),
    getProgramInfoLog: vi.fn(() => ''),
    createBuffer: vi.fn(() => ({} as WebGLBuffer)),
    useProgram: vi.fn(),
    bindBuffer: vi.fn(),
    bufferData: vi.fn(),
    getAttribLocation: vi.fn(() => 0),
    enableVertexAttribArray: vi.fn(),
    vertexAttribPointer: vi.fn(),
    getUniformLocation: vi.fn(() => ({} as WebGLUniformLocation)),
    uniform1f: vi.fn(),
    viewport: vi.fn(),
    clearColor: vi.fn(),
    clear: vi.fn(),
    enable: vi.fn(),
    blendFunc: vi.fn(),
    drawArrays: vi.fn(),
  };
}

function createMockContext2D(): Partial<CanvasRenderingContext2D> {
  return {
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
    closePath: vi.fn(),
    quadraticCurveTo: vi.fn(),
    fillStyle: '',
    strokeStyle: '',
    lineWidth: 1,
    lineCap: 'round',
    lineJoin: 'round',
    shadowBlur: 0,
    shadowColor: '',
    filter: 'none',
    globalCompositeOperation: 'source-over',
  };
}
