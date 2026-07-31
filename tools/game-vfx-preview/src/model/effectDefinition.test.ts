import { createDefaultParameters, updateParameter, type EffectState, type ParameterSpec } from './effectDefinition';

describe('effect definition model', () => {
  const specs: ParameterSpec[] = [
    { key: 'a', label: 'A', min: 0, max: 1, step: 0.1, defaultValue: 0.5 },
    { key: 'b', label: 'B', min: 0, max: 10, step: 1, defaultValue: 3 },
  ];

  it('creates a default parameter dictionary from specs', () => {
    expect(createDefaultParameters(specs)).toEqual({ a: 0.5, b: 3 });
  });

  it('updates a single parameter without mutating the original state', () => {
    const state: EffectState = { effectId: 'x', parameters: { a: 0.5, b: 3 } };
    const next = updateParameter(state, 'a', 0.9);

    expect(next.parameters).toEqual({ a: 0.9, b: 3 });
    expect(next).not.toBe(state);
    expect(state.parameters.a).toBe(0.5);
  });
});
