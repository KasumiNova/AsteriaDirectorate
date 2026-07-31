import { createEffectRenderer, createFullscreenTriangle } from './effectRenderer';

describe('generic WebGL effect renderer', () => {
  it('creates a single full-screen triangle covering clip space', () => {
    expect(Array.from(createFullscreenTriangle())).toEqual([
      -1, -1,
      3, -1,
      -1, 3,
    ]);
  });

  it('reports missing WebGL support clearly', () => {
    const canvas = {
      getContext: () => null,
    } as unknown as HTMLCanvasElement;

    expect(() => createEffectRenderer(canvas)).toThrow('WebGL is not available');
  });
});
