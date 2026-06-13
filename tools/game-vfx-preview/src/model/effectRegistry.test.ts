import { createDefaultEffectState, EFFECTS, findEffect } from './effectRegistry';

describe('effect registry', () => {
  it('registers the rotating blue starburst as the first effect', () => {
    expect(EFFECTS[0]).toEqual(expect.objectContaining({ id: 'rotating-blue-starburst' }));
  });

  it('registers all bundled effect variants', () => {
    const ids = EFFECTS.map((effect) => effect.id);
    expect(ids).toEqual(
      expect.arrayContaining([
        'rotating-blue-starburst',
        'crimson-impact-flare',
        'shockwave-ring',
        'plasma-core',
        'ion-lance-sweep',
        'phase-rift-slit',
        'void-cutter-beam',
      ]),
    );
    expect(ids).not.toContain('gravity-well-vortex');
    expect(ids).not.toContain('magnetic-arc-cage');
    expect(ids).not.toContain('kinetic-shard-bolt');
    expect(ids).not.toContain('fusion-comet-round');
    expect(ids).not.toContain('photon-ribbon-beam');
    expect(ids).not.toContain('aegis-hex-bloom');
    expect(ids).not.toContain('temporal-echo-field');
    expect(ids).not.toContain('crimson-cyberspace-spiral');
  });

  it('keeps effect ids unique', () => {
    const ids = EFFECTS.map((effect) => effect.id);
    expect(new Set(ids).size).toBe(ids.length);
  });

  it('models every effect as a full-screen fragment pass, not Canvas line drawing', () => {
    for (const effect of EFFECTS) {
      expect(effect.fragmentShader).toContain('void main');
      expect(effect.fragmentShader).toContain('gl_FragColor');
      expect(effect.fragmentShader).not.toContain('lineTo');
      expect(effect.fragmentShader).not.toContain('stroke');
      expect(effect.loopSeconds).toBeGreaterThan(0);
      expect(effect.parameters.length).toBeGreaterThan(0);
    }
  });

  it('binds every declared parameter to a matching uniform in its shader', () => {
    for (const effect of EFFECTS) {
      for (const spec of effect.parameters) {
        expect(effect.fragmentShader).toContain(`u_${spec.key}`);
      }
    }
  });

  it('builds default state with finite parameter values', () => {
    const state = createDefaultEffectState('crimson-impact-flare');

    expect(state.effectId).toBe('crimson-impact-flare');
    expect(Object.values(state.parameters).every(Number.isFinite)).toBe(true);
  });

  it('throws on an unknown effect id', () => {
    expect(() => findEffect('does-not-exist')).toThrow('Unknown effect');
  });
});
