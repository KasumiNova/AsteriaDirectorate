import { FULLSCREEN_VERTEX_SHADER, GLSL_COMMON } from './shaderChunks';

describe('shared shader chunks', () => {
  it('provides a full-screen vertex shader exposing a_position', () => {
    expect(FULLSCREEN_VERTEX_SHADER).toContain('a_position');
    expect(FULLSCREEN_VERTEX_SHADER).toContain('gl_Position');
  });

  it('declares precision and built-in uniforms once in the common header', () => {
    expect(GLSL_COMMON).toContain('precision');
    expect(GLSL_COMMON).toContain('uniform float u_time');
    expect(GLSL_COMMON).toContain('uniform vec2 u_resolution');
  });

  it('exposes reusable helpers shared by all effects', () => {
    expect(GLSL_COMMON).toContain('centeredAspect');
    expect(GLSL_COMMON).toContain('hsv2rgb');
    expect(GLSL_COMMON).toContain('fbm');
    expect(GLSL_COMMON).toContain('acesTonemap');
  });
});
