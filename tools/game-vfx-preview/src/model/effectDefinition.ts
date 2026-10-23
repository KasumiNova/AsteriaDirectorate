export type EffectParameters = Record<string, number>;

export interface ParameterSpec {
  /** 对应 fragment shader 中的 uniform `u_<key>`。 */
  key: string;
  label: string;
  min: number;
  max: number;
  step: number;
  defaultValue: number;
}

export interface EffectDefinition {
  id: string;
  name: string;
  description: string;
  loopSeconds: number;
  /** 渲染器会前置公共 GLSL 头，此处只声明 `parameters` 对应的 uniform。 */
  fragmentShader: string;
  parameters: ParameterSpec[];
}

export interface EffectState {
  effectId: string;
  parameters: EffectParameters;
}

export function createDefaultParameters(specs: ParameterSpec[]): EffectParameters {
  const parameters: EffectParameters = {};
  for (const spec of specs) {
    parameters[spec.key] = spec.defaultValue;
  }
  return parameters;
}

export function updateParameter(state: EffectState, key: string, value: number): EffectState {
  return {
    ...state,
    parameters: {
      ...state.parameters,
      [key]: value,
    },
  };
}
