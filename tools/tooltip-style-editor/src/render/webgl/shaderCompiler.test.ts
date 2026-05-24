import { describe, expect, it } from 'vitest';
import { createDefaultFragmentShader, validateFragmentShaderSource } from './shaderCompiler';

describe('shaderCompiler', () => {
  it('creates a default WebGL1 fragment shader with required uniforms', () => {
    const source = createDefaultFragmentShader();

    expect(source).toContain('precision mediump float');
    expect(source).toContain('uniform float u_time');
    expect(source).toContain('uniform vec2 u_resolution');
  });

  it('rejects WebGL2-only fragment shader syntax', () => {
    const result = validateFragmentShaderSource('#version 300 es\nout vec4 color;');

    expect(result.ok).toBe(false);
  });

  it('accepts the default fragment shader', () => {
    expect(validateFragmentShaderSource(createDefaultFragmentShader()).ok).toBe(true);
  });
});
